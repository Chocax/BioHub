package co.edu.unbosque.biohub.gateway.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import co.edu.unbosque.biohub.gateway.config.PropiedadesLimites;
import co.edu.unbosque.biohub.gateway.config.PropiedadesTeselas;
import co.edu.unbosque.biohub.gateway.domain.ImagenTesela;
import co.edu.unbosque.biohub.gateway.domain.LimitadorPeticiones;
import co.edu.unbosque.biohub.gateway.domain.OrigenTeselas;
import co.edu.unbosque.biohub.gateway.domain.ResultadoLimite;
import co.edu.unbosque.biohub.gateway.domain.Tesela;

/**
 * Sirve las teselas del mapa de web-interna desde el gateway (SEC-J-04).
 *
 * <p><b>Por qué existe:</b> si el navegador pidiera las teselas a OpenStreetMap, OSM vería la IP del científico,
 * la hora y el área exacta que mira, que es justo donde está un hallazgo protegido. Con este proxy OSM solo ve al
 * gateway, y la caché compartida mezcla las teselas de todos los usuarios: una tesela que ya está en caché ni
 * siquiera sale a internet.
 *
 * <p><b>Reglas:</b>
 * <ul>
 *   <li>Solo teselas válidas y hasta {@code zoomMaximo} (el mismo tope que el mapa).</li>
 *   <li>Cada usuario tiene un cupo por minuto, así nadie usa el gateway para descargar el mapa entero.</li>
 *   <li>Como máximo {@code descargasSimultaneas} descargas a la vez, como pide la política de uso de OSM.</li>
 *   <li>Si el origen no responde, el circuit breaker corta los intentos (igual que con los servicios, D52).</li>
 *   <li>Nunca se escriben en el log las coordenadas de una tesela: delatarían el área del hallazgo.</li>
 * </ul>
 */
@Service
public class ServicioTeselas implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ServicioTeselas.class);

    /** Nombre del destino en el circuit breaker. */
    static final String DESTINO = "teselas";

    /** Descargas que pueden esperar turno; si hay más, se responde 503 en vez de acumular hilos. */
    private static final int COLA_DESCARGAS = 64;

    private final OrigenTeselas origen;
    private final LimitadorPeticiones limitador;
    private final CircuitoServicios circuito;
    private final PropiedadesTeselas propiedades;
    private final PropiedadesLimites limites;
    private final ThreadPoolExecutor descargador;
    private final AsyncCache<Tesela, ImagenTesela> cache;
    private final long esperaMaximaMs;

    public ServicioTeselas(OrigenTeselas origen, LimitadorPeticiones limitador, CircuitoServicios circuito,
                           PropiedadesTeselas propiedades, PropiedadesLimites limites) {
        this.origen = origen;
        this.limitador = limitador;
        this.circuito = circuito;
        this.propiedades = propiedades;
        this.limites = limites;
        int hilos = propiedades.descargasSimultaneas();
        AtomicInteger contador = new AtomicInteger();
        this.descargador = new ThreadPoolExecutor(hilos, hilos, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(COLA_DESCARGAS), tarea -> {
                    Thread hilo = new Thread(tarea, "teselas-" + contador.incrementAndGet());
                    hilo.setDaemon(true);
                    return hilo;
                }, new ThreadPoolExecutor.AbortPolicy());
        // Caché asíncrona: dos peticiones de la misma tesela comparten una sola descarga, y una descarga lenta
        // no bloquea las demás claves. Un fallo no queda guardado.
        this.cache = Caffeine.newBuilder()
                .maximumWeight(propiedades.cacheMaximo().toBytes())
                .weigher((Tesela tesela, ImagenTesela imagen) -> imagen.bytes().length)
                .expireAfterWrite(propiedades.vidaCache())
                .buildAsync();
        this.esperaMaximaMs = propiedades.timeoutConexion().plus(propiedades.timeoutLectura()).toMillis();
    }

    /**
     * Devuelve la tesela para el usuario {@code usuario} (el {@code sub} del JWT).
     *
     * @throws DatosRechazadosException  si la tesela no existe o supera el zoom máximo (400)
     * @throws LimiteExcedidoException   si el usuario superó su cupo (429)
     * @throws CircuitoAbiertoException  si el origen está caído o saturado (503)
     * @throws TeselaNoDisponibleException si el origen respondió algo que no es una imagen (502)
     */
    public ImagenTesela obtener(String usuario, int z, int x, int y) {
        Tesela tesela;
        try {
            tesela = Tesela.de(z, x, y, propiedades.zoomMaximo());
        } catch (IllegalArgumentException e) {
            throw new DatosRechazadosException("La tesela pedida no existe o supera el zoom permitido.");
        }

        ResultadoLimite cupo = limitador.consumir("teselas:" + usuario, propiedades.porUsuario(), limites.ventana());
        if (!cupo.permitido()) {
            throw new LimiteExcedidoException(cupo.segundosParaReintentar());
        }

        CompletableFuture<ImagenTesela> futura;
        try {
            futura = cache.get(tesela, (clave, ignorado) ->
                    CompletableFuture.supplyAsync(() -> descargar(clave), descargador));
        } catch (RejectedExecutionException e) {
            throw new CircuitoAbiertoException(1);
        }
        return esperar(tesela, futura);
    }

    private ImagenTesela esperar(Tesela tesela, CompletableFuture<ImagenTesela> futura) {
        try {
            return futura.get(esperaMaximaMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new CircuitoAbiertoException(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CircuitoAbiertoException(1);
        } catch (ExecutionException e) {
            // Caffeine quita las descargas fallidas, pero en segundo plano: se quita aquí para que la siguiente
            // petición vuelva a intentar en vez de recibir el mismo error.
            cache.asMap().remove(tesela, futura);
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new TeselaNoDisponibleException("Error inesperado al descargar la tesela");
        }
    }

    /** Corre en un hilo del descargador. El circuit breaker decide si vale la pena intentar. */
    private ImagenTesela descargar(Tesela tesela) {
        circuito.permitir(DESTINO);
        try {
            ImagenTesela imagen = origen.descargar(tesela);
            circuito.registrarExito(DESTINO);
            return imagen;
        } catch (ResourceAccessException e) {
            circuito.registrarFallo(DESTINO);
            log.warn("El servidor de teselas no responde");
            // El mensaje original trae la URL con z/x/y, que delata el área: no debe llegar a ningún log.
            throw new ResourceAccessException("El servidor de teselas no responde");
        } catch (RuntimeException e) {
            circuito.liberar(DESTINO);
            throw e;
        }
    }

    @Override
    public void destroy() {
        descargador.shutdownNow();
    }
}
