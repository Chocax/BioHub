package co.edu.unbosque.biohub.reportes.service;

import java.util.UUID;

/** Evento interno: se guardó una solicitud y, tras el commit, hay que mandarla a la cola (SD-08). */
public record SolicitudReporteCreada(UUID solicitudId) {
}
