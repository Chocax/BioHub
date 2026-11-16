package co.edu.unbosque.biohub.reportes.service;

/** Se pidió el archivo de un reporte que todavía no está listo o que falló (409). */
public class ReporteNoListoException extends RuntimeException {

    public ReporteNoListoException(String estado) {
        super("El reporte todavía no está listo (estado " + estado + ").");
    }
}
