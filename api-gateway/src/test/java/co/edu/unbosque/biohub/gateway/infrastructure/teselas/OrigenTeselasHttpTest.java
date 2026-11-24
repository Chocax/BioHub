package co.edu.unbosque.biohub.gateway.infrastructure.teselas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import co.edu.unbosque.biohub.gateway.config.PropiedadesTeselas;
import co.edu.unbosque.biohub.gateway.domain.ImagenTesela;
import co.edu.unbosque.biohub.gateway.domain.Tesela;
import co.edu.unbosque.biohub.gateway.service.TeselaNoDisponibleException;

/** SEC-J-04: qué ve el servidor de teselas cuando el gateway le pide una (servidor HTTP real y local). */
class OrigenTeselasHttpTest {

    private static final String AGENTE = "BioHub-prueba/1.0";

    private HttpServer servidor;
    private final Map<String, List<String>> cabecerasRecibidas = new ConcurrentHashMap<>();
    private final AtomicInteger peticiones = new AtomicInteger();
    private volatile String rutaRecibida;
    private volatile int estado = 200;
    private volatile String tipo = "image/png";
    private volatile byte[] cuerpo = {(byte) 0x89, 'P', 'N', 'G'};

    @BeforeEach
    void arrancar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.createContext("/", this::responder);
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    private void responder(HttpExchange intercambio) throws IOException {
        peticiones.incrementAndGet();
        rutaRecibida = intercambio.getRequestURI().getPath();
        intercambio.getRequestHeaders().forEach((nombre, valores) -> cabecerasRecibidas.put(nombre.toLowerCase(), valores));
        if (tipo != null) {
            intercambio.getResponseHeaders().set("Content-Type", tipo);
        }
        if (estado == 302) {
            intercambio.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data");
        }
        intercambio.sendResponseHeaders(estado, cuerpo.length == 0 ? -1 : cuerpo.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(cuerpo);
        }
    }

    private OrigenTeselasHttp origen(int puerto) {
        return new OrigenTeselasHttp(new PropiedadesTeselas("http://127.0.0.1:" + puerto + "/{z}/{x}/{y}.png", AGENTE,
                12, 600, null, null, null, 2, DataSize.ofBytes(16), Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private OrigenTeselasHttp origen() {
        return origen(servidor.getAddress().getPort());
    }

    @Test
    void pideLaTeselaSoloConUserAgentYAccept() {
        ImagenTesela imagen = origen().descargar(new Tesela(5, 10, 12));

        assertThat(imagen.tipoContenido()).isEqualTo("image/png");
        assertThat(imagen.bytes()).hasSize(4);
        assertThat(rutaRecibida).isEqualTo("/5/10/12.png");
        assertThat(cabecerasRecibidas.get("user-agent")).containsExactly(AGENTE);
        // Nada que identifique al científico ni a la página llega al origen.
        assertThat(cabecerasRecibidas).doesNotContainKeys("referer", "authorization", "cookie", "x-forwarded-for",
                "forwarded", "x-real-ip", "origin");
    }

    @Test
    void unaRespuestaQueNoEsImagenSeRechaza() {
        tipo = "text/html";

        assertThatThrownBy(() -> origen().descargar(new Tesela(1, 0, 0)))
                .isInstanceOf(TeselaNoDisponibleException.class);
    }

    @Test
    void unEstadoDistintoDe200SeRechaza() {
        estado = 404;

        assertThatThrownBy(() -> origen().descargar(new Tesela(1, 0, 0)))
                .isInstanceOf(TeselaNoDisponibleException.class);
    }

    @Test
    void noSigueRedirecciones() {
        estado = 302;

        assertThatThrownBy(() -> origen().descargar(new Tesela(1, 0, 0)))
                .isInstanceOf(TeselaNoDisponibleException.class);
        assertThat(peticiones).hasValue(1);
    }

    @Test
    void unaTeselaDemasiadoGrandeSeRechaza() {
        cuerpo = new byte[17];

        assertThatThrownBy(() -> origen().descargar(new Tesela(1, 0, 0)))
                .isInstanceOf(TeselaNoDisponibleException.class);
    }

    @Test
    void sinServidorEsUnFalloDeConexion() {
        int puerto = servidor.getAddress().getPort();
        servidor.stop(0);

        assertThatThrownBy(() -> origen(puerto).descargar(new Tesela(1, 0, 0)))
                .isInstanceOf(ResourceAccessException.class);
    }
}
