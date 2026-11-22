package co.edu.unbosque.biohub.cientifico.registro.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import co.edu.unbosque.biohub.cientifico.registro.domain.VersionIncompletaException;
import co.edu.unbosque.biohub.cientifico.registro.service.DatosHallazgoInvalidosException;
import co.edu.unbosque.biohub.cientifico.registro.service.HallazgoNoEncontradoException;
import co.edu.unbosque.biohub.cientifico.registro.service.EvidenciaNoEncontradaException;
import co.edu.unbosque.biohub.cientifico.registro.service.PlantillaNoEncontradaException;
import co.edu.unbosque.biohub.cientifico.registro.service.RepublicacionEnCursoException;
import co.edu.unbosque.biohub.cientifico.registro.service.UbicacionDelHallazgoNoRegistradaException;

/**
 * Traduce las excepciones del módulo registro a {@code application/problem+json}.
 *
 * <p>Incluye los errores que nacen en el módulo de ubicación: cuando rechaza los datos, su excepción
 * viaja por el evento de dominio síncrono, revierte la transacción y el servicio la traduce a un error
 * propio (ver {@code TraductorErroresDeUbicacion}). Para quien llama, un dato de ubicación inválido es
 * un 400 igual que cualquier campo del formulario, con el nombre del que falló (contrato §6).
 *
 * <p>Los mensajes de estas excepciones están pensados para mostrarse: ninguno incluye coordenadas.
 */
@RestControllerAdvice(basePackages = "co.edu.unbosque.biohub.cientifico.registro")
public class RegistroExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RegistroExceptionHandler.class);
    private static final String TIPO_VALIDACION = "https://biohub/errores/validacion";
    private static final String TIPO_NO_ENCONTRADO = "https://biohub/errores/no-encontrado";
    private static final String TIPO_CONFLICTO = "https://biohub/errores/conflicto";

    /** Datos del formulario que no cumplen las reglas de registro (HU-01, HU-03). */
    @ExceptionHandler(DatosHallazgoInvalidosException.class)
    public ResponseEntity<ProblemaRegistroResponse> datosInvalidos(DatosHallazgoInvalidosException e,
                                                                   WebRequest peticion) {
        return validacion(e.campo(), e.getMessage(), "DATOS_INVALIDOS", peticion);
    }

    /** El Builder del historial recibió una versión incompleta o sin justificación (HU-03, HU-05). */
    @ExceptionHandler(VersionIncompletaException.class)
    public ResponseEntity<ProblemaRegistroResponse> versionIncompleta(VersionIncompletaException e,
                                                                      WebRequest peticion) {
        return validacion(e.campo(), e.getMessage(), "VERSION_INCOMPLETA", peticion);
    }

    @ExceptionHandler(HallazgoNoEncontradoException.class)
    public ResponseEntity<ProblemaRegistroResponse> hallazgoNoEncontrado(HallazgoNoEncontradoException e,
                                                                         WebRequest peticion) {
        return noEncontrado(e.getMessage(), "HALLAZGO_NO_ENCONTRADO", peticion);
    }

    @ExceptionHandler(EvidenciaNoEncontradaException.class)
    public ResponseEntity<ProblemaRegistroResponse> evidenciaNoEncontrada(EvidenciaNoEncontradaException e,
                                                                          WebRequest peticion) {
        return noEncontrado(e.getMessage(), "EVIDENCIA_NO_ENCONTRADA", peticion);
    }

    /** El archivo pasa del máximo de multipart (spring.servlet.multipart.max-file-size). */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemaRegistroResponse> archivoDemasiadoGrande(WebRequest peticion) {
        String mensaje = "El archivo supera el tamaño máximo permitido.";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemaRegistroResponse(TIPO_VALIDACION, "Archivo demasiado grande",
                        HttpStatus.PAYLOAD_TOO_LARGE.value(), mensaje, ruta(peticion), "ARCHIVO_DEMASIADO_GRANDE",
                        List.of(new ErrorCampoRegistro("archivo", mensaje))));
    }

    /** Falta el campo {@code archivo} o la petición no es multipart. */
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    public ResponseEntity<ProblemaRegistroResponse> archivoAusente(WebRequest peticion) {
        return validacion("archivo", "Adjunta el archivo en el campo 'archivo'.", "DATOS_INVALIDOS", peticion);
    }

    @ExceptionHandler(PlantillaNoEncontradaException.class)
    public ResponseEntity<ProblemaRegistroResponse> plantillaNoEncontrada(PlantillaNoEncontradaException e,
                                                                          WebRequest peticion) {
        return noEncontrado(e.getMessage(), "PLANTILLA_NO_ENCONTRADA", peticion);
    }

    /**
     * El hallazgo no tenía ubicación registrada antes. Es una inconsistencia entre módulos, no un error
     * del usuario: se responde 409 y queda en el log para revisarlo.
     */
    @ExceptionHandler(UbicacionDelHallazgoNoRegistradaException.class)
    public ResponseEntity<ProblemaRegistroResponse> ubicacionNoRegistrada(
            UbicacionDelHallazgoNoRegistradaException e, WebRequest peticion) {
        log.warn("Se intento actualizar la ubicacion de un hallazgo que no la tenia registrada");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemaRegistroResponse(TIPO_NO_ENCONTRADO, "Conflicto", HttpStatus.CONFLICT.value(),
                        "El hallazgo no tiene ubicacion registrada", ruta(peticion), "UBICACION_NO_REGISTRADA", null));
    }

    /** Ya hay una republicación de fotos públicas en curso (SEC-I27-7). */
    @ExceptionHandler(RepublicacionEnCursoException.class)
    public ResponseEntity<ProblemaRegistroResponse> republicacionEnCurso(RepublicacionEnCursoException e,
                                                                         WebRequest peticion) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemaRegistroResponse(TIPO_CONFLICTO, "Conflicto", HttpStatus.CONFLICT.value(),
                        e.getMessage(), ruta(peticion), "CONFLICTO", null));
    }

    private static ResponseEntity<ProblemaRegistroResponse> validacion(String campo, String mensaje, String codigo,
                                                                       WebRequest peticion) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemaRegistroResponse(TIPO_VALIDACION, "Datos invalidos",
                        HttpStatus.BAD_REQUEST.value(), mensaje, ruta(peticion), codigo,
                        List.of(new ErrorCampoRegistro(campo, mensaje))));
    }

    private static ResponseEntity<ProblemaRegistroResponse> noEncontrado(String mensaje, String codigo,
                                                                         WebRequest peticion) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemaRegistroResponse(TIPO_NO_ENCONTRADO, "No encontrado",
                        HttpStatus.NOT_FOUND.value(), mensaje, ruta(peticion), codigo, null));
    }

    private static String ruta(WebRequest peticion) {
        return peticion.getDescription(false).replaceFirst("^uri=", "");
    }
}
