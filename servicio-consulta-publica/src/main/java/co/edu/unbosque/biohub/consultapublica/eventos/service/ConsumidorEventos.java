package co.edu.unbosque.biohub.consultapublica.eventos.service;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.consultapublica.eventos.domain.EventoSobre;

/**
 * Consume la cola de eventos de este servicio. Valida el sobre; si es inválido lo rechaza sin reencolar
 * (el broker lo envía a la DLQ). En el log solo se registran id, tipo y origen (nunca los datos).
 */
@Component
public class ConsumidorEventos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEventos.class);

    /** Tipos conocidos: la prueba y los tres eventos reales hallazgo.* (contrato 04, §7). */
    static final Set<String> TIPOS_CONOCIDOS = Set.of("prueba.ping", "hallazgo.registrado",
            "hallazgo.reclasificado", "hallazgo.actualizado");
    static final String ORIGEN_CIENTIFICO = ValidadorSobre.ORIGEN_CIENTIFICO;
    static final int TAMANO_MAXIMO_BYTES = 64 * 1024;
    static final int MENSAJE_PRUEBA_MAX = 200;

    private final ValidadorSobre sobre;
    private final AlmacenEventosRecibidos almacen;
    private final ProcesadorHallazgos procesador;

    public ConsumidorEventos(ObjectMapper objectMapper, AlmacenEventosRecibidos almacen,
                             ProcesadorHallazgos procesador,
                             @Value("${biohub.eventos.verificar-publicador:true}") boolean verificarPublicador,
                             @Value("${biohub.eventos.publicador-cientifico:cientifico_mq}") String publicadorEsperado) {
        this.sobre = new ValidadorSobre(objectMapper, verificarPublicador, publicadorEsperado);
        this.almacen = almacen;
        this.procesador = procesador;
        if (!verificarPublicador) {
            log.warn("SEC-J-01: la verificación del publicador AMQP (BIOHUB_EVENTOS_VERIFICAR_PUBLICADOR) está "
                    + "DESACTIVADA: solo es aceptable en local/pruebas; en un despliegue real debe ser true");
        }
    }

    /**
     * Punto de entrada. Un evento con coordenadas (en una clave o en texto libre) se DESCARTA
     * con ack y se registra un incidente sin valores: NO va a la DLQ, que lo retendría en claro y sin TTL
     * (SEC-J-03, RNF9/ASR6). Cualquier otro rechazo sigue el camino normal hacia la DLQ.
     */
    @RabbitListener(queues = "${biohub.eventos.cola}")
    public void recibir(Message mensaje) {
        try {
            // Se analizan todos los mensajes, no solo los de routing key hallazgo.*: un cuerpo hallazgo.* publicado con
            // otra routing key también terminaría en la DLQ
            if (sobre.traeCoordenadas(mensaje.getBody(), false)) {
                throw new EventoConCoordenadasException();
            }
            procesar(mensaje);
        } catch (EventoConCoordenadasException e) {
            sobre.registrarIncidente(mensaje);
        }
    }

    private void procesar(Message mensaje) {
        EventoSobre evento = interpretar(mensaje);
        log.info("Evento recibido id={} tipo={} origen={}", evento.id(), evento.tipo(), evento.origen());
        if (evento.tipo().startsWith("hallazgo.")) {
            sobre.validarOrigen(evento, mensaje);
            procesador.procesar(evento);
        } else {
            almacen.agregar(evento);
        }
    }

    EventoSobre interpretar(Message mensaje) {
        EventoSobre evento = sobre.interpretar(mensaje, TAMANO_MAXIMO_BYTES, TIPOS_CONOCIDOS);
        if (evento.tipo().startsWith("prueba.")) {
            validarPrueba(evento);
        }
        return evento;
    }

    private static void validarPrueba(EventoSobre evento) {
        Object m = evento.datos() == null ? null : evento.datos().get("mensaje");
        if (!(m instanceof String texto) || texto.length() > MENSAJE_PRUEBA_MAX) {
            throw new EventoInvalidoException("Evento de prueba con datos inválidos");
        }
    }
}
