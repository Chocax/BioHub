package co.edu.unbosque.biohub.equipos.controller;

import co.edu.unbosque.biohub.equipos.infrastructure.GatewayCustodioAdapter.CustodioInvalidoException;
import co.edu.unbosque.biohub.equipos.infrastructure.GatewayCustodioAdapter.GatewayNoDisponibleException;
import co.edu.unbosque.biohub.equipos.service.EquipoTrabajoService.ConflictoEquipoException;
import co.edu.unbosque.biohub.equipos.service.EquipoTrabajoService.EquipoNoEncontradoException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class EquiposExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validacion(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<CampoError> errores = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new CampoError(f.getField(), f.getDefaultMessage()))
            .toList();
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "Parámetros inválidos",
            "Revisa los datos enviados.", req.getRequestURI(), "PARAMETRO_INVALIDO");
        pd.setProperty("errores", errores);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler({IllegalArgumentException.class, CustodioInvalidoException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> argumentoInvalido(RuntimeException ex, HttpServletRequest req) {
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
            ex.getMessage(), req.getRequestURI(), "PARAMETRO_INVALIDO");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(EquipoNoEncontradoException.class)
    public ResponseEntity<ProblemDetail> noEncontrado(EquipoNoEncontradoException ex, HttpServletRequest req) {
        ProblemDetail pd = problema(HttpStatus.NOT_FOUND, "No encontrado",
            ex.getMessage(), req.getRequestURI(), "NO_ENCONTRADO");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(ConflictoEquipoException.class)
    public ResponseEntity<ProblemDetail> conflicto(ConflictoEquipoException ex, HttpServletRequest req) {
        ProblemDetail pd = problema(HttpStatus.CONFLICT, "Conflicto",
            ex.getMessage(), req.getRequestURI(), "CONFLICTO");
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> denegado(HttpServletRequest req) {
        ProblemDetail pd = problema(HttpStatus.FORBIDDEN, "Acceso denegado",
            "No tienes permiso para realizar esta acción.", req.getRequestURI(), "ROL_INSUFICIENTE");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(GatewayNoDisponibleException.class)
    public ResponseEntity<ProblemDetail> noDisponible(GatewayNoDisponibleException ex, HttpServletRequest req) {
        ProblemDetail pd = problema(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible",
            ex.getMessage(), req.getRequestURI(), "SERVICIO_NO_DISPONIBLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    private ProblemDetail problema(HttpStatus status, String title, String detail, String instance, String codigo) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setInstance(URI.create(instance));
        pd.setProperty("codigo", codigo);
        return pd;
    }

    private record CampoError(String campo, String mensaje) {}
}
