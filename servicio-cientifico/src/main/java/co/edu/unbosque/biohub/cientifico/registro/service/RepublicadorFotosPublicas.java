package co.edu.unbosque.biohub.cientifico.registro.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgoRepository;

import jakarta.annotation.PreDestroy;

/**
 * Vuelve a publicar {@code evidencia.publicada} de las fotos ya públicas (D66): migra las que se marcaron antes de
 * D66 (genera la miniatura que falte) y recupera eventos perdidos. Es idempotente del lado del consumidor, que ordena
 * por {@code cambioEn}.
 *
 * <p>Cada foto va en su propia transacción: dentro se genera la miniatura y se emite el evento de dominio, que el
 * listener de infraestructura envía al broker <b>después del commit</b>. Un fallo con una foto (imagen dañada,
 * broker caído) se registra y no detiene las demás. El trabajo corre en un hilo propio, de a una republicación a la
 * vez, para no bloquear la petición del administrador ni el arranque.
 */
@Service
public class RepublicadorFotosPublicas {

    private static final Logger log = LoggerFactory.getLogger(RepublicadorFotosPublicas.class);
    private static final String SISTEMA = "sistema";

    private final EvidenciaHallazgoRepository evidencias;
    private final FotosPublicas fotosPublicas;
    private final TransactionTemplate transaccion;
    private final Executor ejecutor;
    private final ExecutorService propio;
    private final AtomicBoolean enCurso = new AtomicBoolean(false);

    @Autowired
    public RepublicadorFotosPublicas(EvidenciaHallazgoRepository evidencias, FotosPublicas fotosPublicas,
                                     PlatformTransactionManager gestor) {
        this(evidencias, fotosPublicas, gestor, null);
    }

    /** Con un ejecutor dado (las pruebas usan uno síncrono); sin él, un hilo propio. */
    RepublicadorFotosPublicas(EvidenciaHallazgoRepository evidencias, FotosPublicas fotosPublicas,
                              PlatformTransactionManager gestor, Executor ejecutor) {
        this.evidencias = evidencias;
        this.fotosPublicas = fotosPublicas;
        this.transaccion = new TransactionTemplate(gestor);
        if (ejecutor == null) {
            this.propio = Executors.newSingleThreadExecutor(r -> {
                Thread hilo = new Thread(r, "republicador-fotos-publicas");
                hilo.setDaemon(true);
                return hilo;
            });
            this.ejecutor = this.propio;
        } else {
            this.propio = null;
            this.ejecutor = ejecutor;
        }
    }

    /** Resultado de pedir una republicación: cuántas fotos y si ya había otra en curso (entonces no se inició). */
    public record Resultado(int fotos, boolean yaEnCurso) {
    }

    /**
     * Republica todas las fotos públicas, en segundo plano, en nombre de un administrador (SEC-I27-7: queda en el
     * log quién la pidió).
     *
     * @param administrador {@code preferred_username} de quien la pide
     * @return cuántas fotos se van a republicar, o {@code yaEnCurso} si ya había una y no se inició otra
     */
    public Resultado republicarTodas(String administrador) {
        log.info("Republicación de fotos públicas solicitada por {}", administrador);
        return lanzar(evidencias.idsFotosPublicas(), "todas las fotos públicas", administrador);
    }

    /** Todas las fotos públicas, a nombre del sistema. */
    public int republicarTodas() {
        return republicarTodas(SISTEMA).fotos();
    }

    /** Solo las fotos públicas sin miniatura (migración al arrancar). */
    public int republicarSinMiniatura() {
        return lanzar(evidencias.idsFotosPublicasSinMiniatura(), "las fotos públicas sin miniatura", SISTEMA).fotos();
    }

    private Resultado lanzar(List<UUID> ids, String que, String actor) {
        if (ids.isEmpty()) {
            return new Resultado(0, false);
        }
        if (!enCurso.compareAndSet(false, true)) {
            log.info("Ya hay una republicación de fotos públicas en curso; no se inicia otra (pedida por {})", actor);
            return new Resultado(ids.size(), true);
        }
        try {
            ejecutor.execute(() -> {
                try {
                    int publicadas = 0;
                    for (UUID id : ids) {
                        publicadas += republicar(id, actor) ? 1 : 0;
                    }
                    log.info("Republicación de {} pedida por {}: {} de {} fotos", que, actor, publicadas, ids.size());
                } catch (RuntimeException e) {
                    log.error("La republicación de fotos públicas se detuvo: {}", e.getClass().getSimpleName());
                } finally {
                    enCurso.set(false);
                }
            });
        } catch (RuntimeException e) {
            enCurso.set(false);
            throw e;
        }
        return new Resultado(ids.size(), false);
    }

    /** Una foto en su propia transacción; nunca lanza. */
    boolean republicar(UUID evidenciaId, String actor) {
        try {
            return Boolean.TRUE.equals(transaccion.execute(estado -> {
                EvidenciaHallazgo evidencia = evidencias.findById(evidenciaId).orElse(null);
                if (evidencia == null || !evidencia.isPublica()) {
                    return false; // la retiraron mientras tanto
                }
                fotosPublicas.anunciarPublicada(evidencia, actor);
                evidencias.save(evidencia);
                return true;
            }));
        } catch (RuntimeException e) {
            log.error("No se pudo republicar la foto pública {}: {}", evidenciaId, e.getClass().getSimpleName());
            return false;
        }
    }

    @PreDestroy
    void cerrar() {
        if (propio != null) {
            propio.shutdown();
        }
    }
}
