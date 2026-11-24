package co.edu.unbosque.biohub.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;

import co.edu.unbosque.biohub.gateway.config.PropiedadesCircuito;
import co.edu.unbosque.biohub.gateway.config.PropiedadesLimites;
import co.edu.unbosque.biohub.gateway.config.PropiedadesTeselas;
import co.edu.unbosque.biohub.gateway.domain.ImagenTesela;
import co.edu.unbosque.biohub.gateway.domain.LimitadorPeticiones;
import co.edu.unbosque.biohub.gateway.domain.ResultadoLimite;
import co.edu.unbosque.biohub.gateway.domain.Tesela;

/** SEC-J-04: reglas del proxy de teselas sin red (origen y límite falsos). */
@ExtendWith(OutputCaptureExtension.class)
class ServicioTeselasTest {

    private static final ImagenTesela PNG = new ImagenTesela(new byte[] {1, 2, 3}, "image/png");

    private final AtomicInteger descargas = new AtomicInteger();
    private final List<String> clavesLimite = new ArrayList<>();
    private final AtomicBoolean cupoAgotado = new AtomicBoolean();
    private Function<Tesela, ImagenTesela> comportamiento = tesela -> PNG;
    private ServicioTeselas servicio;

    private final LimitadorPeticiones limitador = new LimitadorPeticiones() {
        @Override
        public ResultadoLimite consumir(String clave, int limite, Duration ventana) {
            clavesLimite.add(clave);
            return cupoAgotado.get() ? new ResultadoLimite(false, limite, 0, Duration.ofSeconds(42))
                    : new ResultadoLimite(true, limite, limite - 1, ventana);
        }

        @Override
        public ResultadoLimite consultar(String clave, int limite, Duration ventana) {
            return new ResultadoLimite(true, limite, limite, ventana);
        }
    };

    private ServicioTeselas crear() {
        PropiedadesTeselas propiedades = new PropiedadesTeselas(null, null, 12, 600, null, null, null, 2, null,
                Duration.ofMillis(500), Duration.ofSeconds(2));
        servicio = new ServicioTeselas(tesela -> {
            descargas.incrementAndGet();
            return comportamiento.apply(tesela);
        }, limitador, new CircuitoServicios(new PropiedadesCircuito(2, Duration.ofSeconds(30))),
                propiedades, new PropiedadesLimites(60, 120, 20, Duration.ofSeconds(60)));
        return servicio;
    }

    @AfterEach
    void cerrar() {
        if (servicio != null) {
            servicio.destroy();
        }
    }

    @Test
    void descargaUnaVezYLuegoSirveDesdeLaCache() {
        crear();

        assertThat(servicio.obtener("u1", 5, 9, 15).bytes()).containsExactly(1, 2, 3);
        assertThat(servicio.obtener("u2", 5, 9, 15).tipoContenido()).isEqualTo("image/png");

        assertThat(descargas).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"13,0,0", "-1,0,0", "3,8,0", "3,0,8", "3,-1,0", "0,1,0"})
    void rechazaTeselasQueNoExistenOQueSuperanElZoomSinDescargar(int z, int x, int y) {
        crear();

        assertThatThrownBy(() -> servicio.obtener("u1", z, x, y)).isInstanceOf(DatosRechazadosException.class);
        assertThat(descargas).hasValue(0);
    }

    @Test
    void cadaUsuarioTieneSuPropioCupo() {
        crear();

        servicio.obtener("sub-cientifico", 1, 0, 0);

        assertThat(clavesLimite).containsExactly("teselas:sub-cientifico");
    }

    @Test
    void conElCupoAgotadoResponde429SinDescargar() {
        crear();
        cupoAgotado.set(true);

        assertThatThrownBy(() -> servicio.obtener("u1", 1, 0, 0))
                .isInstanceOfSatisfying(LimiteExcedidoException.class,
                        e -> assertThat(e.segundosParaReintentar()).isEqualTo(42));
        assertThat(descargas).hasValue(0);
    }

    @Test
    void unOrigenCaidoAbreElCircuitoYDejaDeIntentar() {
        comportamiento = tesela -> {
            throw new ResourceAccessException("sin conexión");
        };
        crear();

        assertThatThrownBy(() -> servicio.obtener("u1", 1, 0, 0)).isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> servicio.obtener("u1", 1, 0, 1)).isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> servicio.obtener("u1", 1, 1, 0)).isInstanceOf(CircuitoAbiertoException.class);

        assertThat(descargas).hasValue(2);
    }

    @Test
    void lasCoordenadasDeLaTeselaNoLleganAlLogNiAlError(CapturedOutput salida) {
        comportamiento = tesela -> {
            throw new ResourceAccessException("I/O error on GET request for \"https://tile.example/7/50/60.png\"");
        };
        crear();

        assertThatThrownBy(() -> servicio.obtener("u1", 7, 50, 60))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageNotContaining("7/50/60");
        assertThat(salida.getAll()).doesNotContain("7/50/60");
    }

    @Test
    void unaRespuestaInvalidaNoQuedaGuardada() {
        AtomicInteger intento = new AtomicInteger();
        comportamiento = tesela -> {
            if (intento.incrementAndGet() == 1) {
                throw new TeselaNoDisponibleException("no es imagen");
            }
            return PNG;
        };
        crear();

        assertThatThrownBy(() -> servicio.obtener("u1", 2, 1, 1)).isInstanceOf(TeselaNoDisponibleException.class);
        assertThat(servicio.obtener("u1", 2, 1, 1).bytes()).containsExactly(1, 2, 3);
        assertThat(descargas).hasValue(2);
    }

    @Test
    void dosPeticionesDeLaMismaTeselaCompartenUnaDescarga() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        comportamiento = tesela -> {
            dentro.countDown();
            try {
                soltar.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PNG;
        };
        crear();

        CompletableFuture<ImagenTesela> primera = CompletableFuture.supplyAsync(() -> servicio.obtener("u1", 4, 3, 3));
        assertThat(dentro.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ImagenTesela> segunda = CompletableFuture.supplyAsync(() -> servicio.obtener("u2", 4, 3, 3));
        soltar.countDown();

        assertThat(primera.get(3, TimeUnit.SECONDS).bytes()).containsExactly(1, 2, 3);
        assertThat(segunda.get(3, TimeUnit.SECONDS).bytes()).containsExactly(1, 2, 3);
        assertThat(descargas).hasValue(1);
    }

    @Test
    void unOrigenQueNoTerminaRespondeServicioNoDisponible() {
        comportamiento = tesela -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PNG;
        };
        crear();

        assertThatThrownBy(() -> servicio.obtener("u1", 1, 1, 1)).isInstanceOf(CircuitoAbiertoException.class);
    }
}
