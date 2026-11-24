package co.edu.unbosque.biohub.reportes.config;

import java.time.Clock;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cola interna de solicitudes de reporte (HU-09, SD-08). Es solo de este servicio: él publica y él consume, así el
 * trabajo pesado corre en segundo plano y la petición del analista responde 202 al instante.
 *
 * <p>Una solicitud que no se puede leer va a {@code reportes.solicitudes.dlq} por el mismo DLX de los eventos.
 */
@Configuration
@EnableScheduling
public class SolicitudesReporteConfig {

    @Bean
    DirectExchange reportesExchange(@Value("${biohub.reportes.exchange}") String nombre) {
        return new DirectExchange(nombre, true, false);
    }

    @Bean
    Queue colaSolicitudes(@Value("${biohub.reportes.cola-solicitudes}") String cola,
                          @Value("${biohub.eventos.dlx}") String dlx) {
        return QueueBuilder.durable(cola).deadLetterExchange(dlx).deadLetterRoutingKey(cola + ".dlq").build();
    }

    @Bean
    Queue colaSolicitudesDlq(@Value("${biohub.reportes.cola-solicitudes}") String cola) {
        return QueueBuilder.durable(cola + ".dlq").build();
    }

    @Bean
    Binding bindingSolicitudes(Queue colaSolicitudes, DirectExchange reportesExchange) {
        return BindingBuilder.bind(colaSolicitudes).to(reportesExchange).with(colaSolicitudes.getName());
    }

    @Bean
    Binding bindingSolicitudesDlq(Queue colaSolicitudesDlq,
                                  org.springframework.amqp.core.TopicExchange eventosDlx) {
        return BindingBuilder.bind(colaSolicitudesDlq).to(eventosDlx).with(colaSolicitudesDlq.getName());
    }

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }
}
