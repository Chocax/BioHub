package co.edu.unbosque.biohub.consultapublica.eventos.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import co.edu.unbosque.biohub.consultapublica.soporte.Eventos;
import co.edu.unbosque.biohub.consultapublica.soporte.Fotos;

/** D66: el consumidor de fotos reutiliza el sobre de hallazgos con sus propios límites (1 MB, sin escanear el JPEG). */
class ConsumidorFotosTest {

    private static final String CAMBIO = "2026-11-21T10:00:00Z";

    private ProcesadorFotos procesador;
    private ConsumidorFotos consumidor;
    private final UUID evidencia = UUID.randomUUID();
    private final UUID hallazgo = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        procesador = mock(ProcesadorFotos.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        consumidor = new ConsumidorFotos(mapper, procesador, true, "cientifico_mq");
    }

    private static Message mensaje(String json, String routingKey, String userId) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        props.setReceivedUserId(userId);
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    @DisplayName("un evento válido llega al procesador, con un JPEG grande en Base64 que no se analiza como coordenadas")
    void valido() {
        byte[] jpeg = Fotos.jpeg(500, 400);
        String json = Eventos.sobre("evidencia.publicada", Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 500, 400));
        consumidor.recibir(mensaje(json, "evidencia.publicada", "cientifico_mq"));
        verify(procesador).procesar(any());
    }

    @Test
    @DisplayName("coordenadas en un campo de texto: descarte con ack, sin procesar (no lanza)")
    void coordenadasSeDescartan() {
        Map<String, Object> datos = Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, Fotos.jpeg(40, 40), 40, 40);
        datos.put("descripcion", "tomada en 5.53214, -73.36712");
        consumidor.recibir(mensaje(Eventos.sobre("evidencia.publicada", datos), "evidencia.publicada", "cientifico_mq"));
        Map<String, Object> clave = Fotos.datosRetirada(evidencia, hallazgo, CAMBIO);
        clave.put("latitud", 5.5);
        consumidor.recibir(mensaje(Eventos.sobre("evidencia.retirada", clave), "evidencia.retirada", "cientifico_mq"));
        verifyNoInteractions(procesador);
    }

    @Test
    @DisplayName("coordenadas escondidas en jpegBase64 (no es Base64 de verdad) también se detectan")
    void coordenadasEnJpegBase64SeDetectan() {
        Map<String, Object> datos = Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, Fotos.jpeg(40, 40), 40, 40);
        datos.put("jpegBase64", "lat: 5.53214, lng: -73.36712");
        consumidor.recibir(mensaje(Eventos.sobre("evidencia.publicada", datos), "evidencia.publicada", "cientifico_mq"));
        verifyNoInteractions(procesador);
    }

    @Test
    @DisplayName("origen distinto, publicador distinto, tipo de hallazgo o routing key distinta: rechazo (DLQ)")
    void rechazos() {
        String json = Eventos.sobre("evidencia.retirada", Fotos.datosRetirada(evidencia, hallazgo, CAMBIO));
        assertThatThrownBy(() -> consumidor.recibir(mensaje(json, "evidencia.retirada", "otro_mq")))
                .isInstanceOf(EventoInvalidoException.class);
        assertThatThrownBy(() -> consumidor.recibir(mensaje(json, "evidencia.retirada", null)))
                .isInstanceOf(EventoInvalidoException.class);
        String otroOrigen = Eventos.sobre("evidencia.retirada", "servicio-reportes", Fotos.datosRetirada(evidencia, hallazgo, CAMBIO));
        assertThatThrownBy(() -> consumidor.recibir(mensaje(otroOrigen, "evidencia.retirada", "cientifico_mq")))
                .isInstanceOf(EventoInvalidoException.class);
        assertThatThrownBy(() -> consumidor.recibir(mensaje(json, "evidencia.publicada", "cientifico_mq")))
                .isInstanceOf(EventoInvalidoException.class);
        String hallazgoEnFotos = Eventos.sobre("hallazgo.registrado", Eventos.datos(hallazgo, 1, "Ara macao", "SIN_EVALUAR"));
        assertThatThrownBy(() -> consumidor.recibir(mensaje(hallazgoEnFotos, "hallazgo.registrado", "cientifico_mq")))
                .isInstanceOf(EventoInvalidoException.class);
        verifyNoInteractions(procesador);
    }

    @Test
    @DisplayName("más de 1 MB: DLQ")
    void masDeUnMegabyte() {
        Map<String, Object> datos = Fotos.datosRetirada(evidencia, hallazgo, CAMBIO);
        datos.put("relleno", "x".repeat(ConsumidorFotos.TAMANO_MAXIMO_BYTES));
        String json = Eventos.sobre("evidencia.retirada", datos);
        assertThatThrownBy(() -> consumidor.recibir(mensaje(json, "evidencia.retirada", "cientifico_mq")))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("tamaño");
    }
}
