package co.edu.unbosque.biohub.reportes.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ReportesExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> parametroInvalido(Exception ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Parámetros inválidos");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("codigo", "PARAMETRO_INVALIDO");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> denegado(HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
            "No tienes permiso para acceder a este reporte.");
        pd.setTitle("Acceso denegado");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("codigo", "ROL_INSUFICIENTE");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(co.edu.unbosque.biohub.reportes.service.SolicitudNoEncontradaException.class)
    public ResponseEntity<ProblemDetail> solicitudNoEncontrada(RuntimeException ex, HttpServletRequest req) {
        return problema(HttpStatus.NOT_FOUND, "No encontrado", ex.getMessage(), "NO_ENCONTRADO", req);
    }

    /** HU-09: el archivo se pide antes de que el reporte esté listo (o el reporte falló). */
    @ExceptionHandler(co.edu.unbosque.biohub.reportes.service.ReporteNoListoException.class)
    public ResponseEntity<ProblemDetail> reporteNoListo(RuntimeException ex, HttpServletRequest req) {
        return problema(HttpStatus.CONFLICT, "Reporte no listo", ex.getMessage(), "REPORTE_NO_LISTO", req);
    }

    private static ResponseEntity<ProblemDetail> problema(HttpStatus estado, String titulo, String detalle,
                                                          String codigo, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(estado, detalle);
        pd.setTitle(titulo);
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("codigo", codigo);
        return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
