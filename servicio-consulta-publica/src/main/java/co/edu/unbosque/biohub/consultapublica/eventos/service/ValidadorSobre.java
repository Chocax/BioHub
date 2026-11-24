package co.edu.unbosque.biohub.consultapublica.eventos.service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.consultapublica.eventos.domain.EventoSobre;

/**
 * Validación común del sobre de los eventos (contrato 04, §7.2 y §7.5), compartida por los consumidores de
 * {@code hallazgo.*} y de {@code evidencia.*} (D66): análisis de coordenadas del cuerpo, interpretación del sobre
 * (tamaño, JSON, campos, tipo conocido, routing key = tipo), origen y {@code user_id} AMQP del publicador. No es un bean:
 * cada consumidor crea la suya con sus propios límites. Nunca registra valores del evento.
 */
final class ValidadorSobre {

    private static final Logger log = LoggerFactory.getLogger(ValidadorSobre.class);

    static final String ORIGEN_CIENTIFICO = "servicio-cientifico";

    private final ObjectMapper objectMapper;
    private final boolean verificarPublicador;
    private final String publicadorEsperado;

    ValidadorSobre(ObjectMapper objectMapper, boolean verificarPublicador, String publicadorEsperado) {
        // SEC-J-01: una clave repetida en el JSON (p. ej. dos "version") es ambigua y se rechaza
        this.objectMapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.verificarPublicador = verificarPublicador;
        this.publicadorEsperado = publicadorEsperado;
    }

    /**
     * Analiza el cuerpo: estructurado si es JSON válido, en bruto si no. Nunca lanza ni registra valores.
     *
     * @param sinJpegBase64 {@code true} en los eventos de foto: {@code datos.jpegBase64} no se analiza si es un Base64
     *                      de verdad (no es texto: son bytes de una imagen y miles de caracteres sin sentido para el
     *                      detector). Si ese campo trae otra cosa (puntos, comas, símbolos de grados...), SÍ se analiza.
     */
    boolean traeCoordenadas(byte[] cuerpo, boolean sinJpegBase64) {
        if (cuerpo == null || cuerpo.length == 0) {
            return false;
        }
        if (cuerpo.length <= DetectorCoordenadas.MAXIMO_BYTES_ANALIZADOS) {
            try {
                Object arbol = objectMapper.readValue(cuerpo, Object.class);
                if (sinJpegBase64) {
                    quitarJpegBase64(arbol);
                }
                return DetectorCoordenadas.contiene(arbol);
            } catch (IOException | RuntimeException e) {
                // JSON roto o no UTF-8: se cae al análisis en bruto
            }
        }
        return DetectorCoordenadas.contieneEnBruto(cuerpo);
    }

    private static void quitarJpegBase64(Object arbol) {
        if (arbol instanceof Map<?, ?> sobre && sobre.get("datos") instanceof Map<?, ?> datos
                && datos.get("jpegBase64") instanceof String texto && esBase64(texto)) {
            datos.remove("jpegBase64");
        }
    }

    /** Solo caracteres del alfabeto Base64 estándar, con el relleno {@code =} al final y saltos de línea. */
    static boolean esBase64(String texto) {
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            boolean valido = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '\r' || c == '\n';
            if (!valido) {
                return false;
            }
        }
        return true;
    }

    void registrarIncidente(Message mensaje) {
        String id = "desconocido";
        String tipo = String.valueOf(mensaje.getMessageProperties().getReceivedRoutingKey())
                .replaceAll("\\p{Cntrl}", "_");
        tipo = tipo.length() > 60 ? tipo.substring(0, 60) : tipo;
        try {
            EventoSobre e = objectMapper.readValue(mensaje.getBody(), EventoSobre.class);
            if (e != null && e.id() != null) {
                id = e.id().toString();
            }
        } catch (IOException | RuntimeException ignorado) {
            // sobre ilegible: el incidente se registra sin id
        }
        log.error("INCIDENTE SEC-J-03: evento con coordenadas DESCARTADO (ack, sin DLQ) id={} tipo={}. "
                + "Avisar al responsable del publicador; no se registran los valores", id, tipo);
    }

    /** D24: solo servicio-cientifico publica estos eventos; opcionalmente se verifica el user_id AMQP (SEC-D-03). */
    void validarOrigen(EventoSobre evento, Message mensaje) {
        if (!ORIGEN_CIENTIFICO.equals(evento.origen())) {
            log.warn("Evento con origen no permitido id={} tipo={}", evento.id(), evento.tipo());
            throw new EventoInvalidoException("Evento con origen no permitido");
        }
        if (verificarPublicador
                && !publicadorEsperado.equals(mensaje.getMessageProperties().getReceivedUserId())) {
            log.warn("Evento con publicador no permitido id={} tipo={}", evento.id(), evento.tipo());
            throw new EventoInvalidoException("Evento con publicador no permitido");
        }
    }

    EventoSobre interpretar(Message mensaje, int tamanoMaximoBytes, Set<String> tiposConocidos) {
        byte[] cuerpo = mensaje.getBody();
        if (cuerpo == null || cuerpo.length == 0 || cuerpo.length > tamanoMaximoBytes) {
            throw new EventoInvalidoException("Evento con tamaño no permitido");
        }
        EventoSobre evento;
        try {
            evento = objectMapper.readValue(cuerpo, EventoSobre.class);
        } catch (IOException | RuntimeException e) {
            throw new EventoInvalidoException("Evento con JSON inválido"); // sin la causa: Jackson puede citar el contenido
        }
        if (evento == null || evento.id() == null || evento.fecha() == null
                || esVacio(evento.tipo()) || esVacio(evento.origen())) {
            throw new EventoInvalidoException("Evento con sobre incompleto");
        }
        if (!tiposConocidos.contains(evento.tipo())) {
            throw new EventoInvalidoException("Evento de tipo desconocido");
        }
        String rk = mensaje.getMessageProperties().getReceivedRoutingKey();
        if (rk != null && !rk.equals(evento.tipo())) {
            throw new EventoInvalidoException("Routing key distinta del tipo del evento");
        }
        return evento.datos() == null
                ? new EventoSobre(evento.id(), evento.tipo(), evento.origen(), evento.fecha(), evento.usuario(), Map.of())
                : evento;
    }

    private static boolean esVacio(String s) {
        return s == null || s.isBlank();
    }
}
