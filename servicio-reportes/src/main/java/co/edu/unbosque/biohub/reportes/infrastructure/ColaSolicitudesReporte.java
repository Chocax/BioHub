package co.edu.unbosque.biohub.reportes.infrastructure;

import java.time.Duration;
import java.time.Instant;
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
            log.error("No se pudo encolar la solicitud de reporte {}", creada.solicitudId());
            servicio.marcarFallido(creada.solicitudId(), "No fue posible encolar la solicitud en RabbitMQ.");
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

    /** Libera solicitudes que hayan quedado colgadas en PENDIENTE o EN_PROCESO por más de 5 minutos. */
    @Scheduled(fixedDelayString = "${biohub.reportes.watchdog-ms:60000}")
    public void limpiarSolicitudesColgadas() {
        int liberadas = servicio.liberarSolicitudesColgadas(Instant.now().minus(Duration.ofMinutes(5)));
        if (liberadas > 0) {
            log.warn("Watchdog de reportes marcó {} solicitudes colgadas como FALLIDO", liberadas);
        }
    }
}
