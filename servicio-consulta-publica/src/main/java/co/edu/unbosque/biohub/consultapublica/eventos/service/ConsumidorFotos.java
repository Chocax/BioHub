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
 * Consume la cola {@code consulta-publica.fotos} (D66, contrato 04 §7.6), enlazada con {@code evidencia.#}. Reutiliza la
 * validación del sobre, el origen y el {@code user_id} de {@link ConsumidorEventos} (clase {@link ValidadorSobre}), con
 * un límite de 1 MB solo para esta cola (las demás siguen en 64 KB). Las coordenadas se buscan en todo el cuerpo salvo
 * en {@code datos.jpegBase64}; si aparecen, el mensaje se descarta con ack y un incidente, sin pasar por la DLQ. Cualquier
 * otro rechazo (foto inválida, con Exif, sha256 malo, origen o publicador distinto) lo manda a la DLQ. Solo se registran
 * id, tipo y origen: nunca los datos ni los bytes.
 */
@Component
public class ConsumidorFotos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorFotos.class);

    static final Set<String> TIPOS_CONOCIDOS = Set.of(ProcesadorFotos.PUBLICADA, ProcesadorFotos.RETIRADA);
    /** Límite del mensaje solo en esta cola (miniatura de hasta 600 KB en Base64). */
    static final int TAMANO_MAXIMO_BYTES = 1024 * 1024;

    private final ValidadorSobre sobre;
    private final ProcesadorFotos procesador;

    public ConsumidorFotos(ObjectMapper objectMapper, ProcesadorFotos procesador,
                           @Value("${biohub.eventos.verificar-publicador:true}") boolean verificarPublicador,
                           @Value("${biohub.eventos.publicador-cientifico:cientifico_mq}") String publicadorEsperado) {
        this.sobre = new ValidadorSobre(objectMapper, verificarPublicador, publicadorEsperado);
        this.procesador = procesador;
    }

    @RabbitListener(queues = "${biohub.eventos.cola-fotos}", containerFactory = "fotosListenerContainerFactory")
    public void recibir(Message mensaje) {
        try {
            if (sobre.traeCoordenadas(mensaje.getBody(), true)) {
                throw new EventoConCoordenadasException();
            }
            EventoSobre evento = sobre.interpretar(mensaje, TAMANO_MAXIMO_BYTES, TIPOS_CONOCIDOS);
            log.info("Evento de foto recibido id={} tipo={} origen={}", evento.id(), evento.tipo(), evento.origen());
            sobre.validarOrigen(evento, mensaje);
            procesador.procesar(evento);
        } catch (EventoConCoordenadasException e) {
            sobre.registrarIncidente(mensaje);
        }
    }
}
