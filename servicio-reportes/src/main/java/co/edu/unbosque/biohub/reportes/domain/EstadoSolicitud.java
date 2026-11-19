package co.edu.unbosque.biohub.reportes.domain;

/** Ciclo de vida de una solicitud de reporte (HU-09, SD-08). */
public enum EstadoSolicitud {
    PENDIENTE,
    EN_PROCESO,
    LISTO,
    FALLIDO
}
