package co.edu.unbosque.biohub.reportes.service;

/** No existe la solicitud de reporte pedida (404). */
public class SolicitudNoEncontradaException extends RuntimeException {

    public SolicitudNoEncontradaException() {
        super("No existe la solicitud de reporte.");
    }
}
