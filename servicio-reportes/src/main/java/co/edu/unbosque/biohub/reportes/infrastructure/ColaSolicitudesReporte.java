package co.edu.unbosque.biohub.reportes.infrastructure;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import co.edu.unbosque.biohub.reportes.service.SolicitudReporteCreada;
import co.edu.unbosque.biohub.reportes.service.SolicitudesReporteService;

/**
 * Adaptador de RabbitMQ para las solicitudes de reporte (SD-08):
 * <ul>
 *   <li>publica cada solicitud nueva <b>después</b> del commit, para no anunciar una que no se guardó;</li>
 *   <li>la consume en segundo plano y llama al servicio que genera el reporte;</li>
 *   <li>cada mes crea sola la solicitud del mes anterior (HU-09 "programar").</li>
 * </ul>
 * El mensaje solo lleva el id de la solicitud: los datos se leen de la base analítica al procesarla.
 */
@Component
public class ColaSolicitudesReporte {

    private static final Logger log = LoggerFactory.getLogger(ColaSolicitudesReporte.class);

    private final RabbitTemplate rabbit;
    private final SolicitudesReporteService servicio;
    private final String exchange;
    private final String cola;

    public ColaSolicitudesReporte(RabbitTemplate rabbit, SolicitudesReporteService servicio,
                                  @Value("${biohub.reportes.exchange}") String exchange,
                                  @Value("${biohub.reportes.cola-solicitudes}") String cola) {
        this.rabbit = rabbit;
        this.servicio = servicio;
        this.exchange = exchange;
        this.cola = cola;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publicar(SolicitudReporteCreada creada) {
        try {
            rabbit.convertAndSend(exchange, cola, creada.solicitudId().toString());
        } catch (AmqpException e) {
            // La solicitud queda PENDIENTE y visible; se puede volver a pedir. No se pierde el registro.
            log.error("No se pudo encolar la solicitud de reporte {}", creada.solicitudId());
        }
    }

    @RabbitListener(queues = "${biohub.reportes.cola-solicitudes}")
    public void recibir(String id) {
        UUID solicitud;
        try {
            solicitud = UUID.fromString(id.strip());
        } catch (IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("Solicitud de reporte con id inválido");
        }
        servicio.procesar(solicitud);
    }

    /** El primer día de cada mes, a las 3 a. m. de Bogotá por defecto. */
    @Scheduled(cron = "${biohub.reportes.programacion:0 0 3 1 * *}", zone = "America/Bogota")
    public void reporteMensualProgramado() {
        if (servicio.solicitarMesAnteriorSiFalta()) {
            log.info("Reporte mensual programado solicitado");
        }
    }
}
