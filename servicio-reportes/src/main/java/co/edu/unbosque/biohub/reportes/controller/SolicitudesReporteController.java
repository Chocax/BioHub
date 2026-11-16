package co.edu.unbosque.biohub.reportes.controller;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.reportes.domain.OrigenSolicitud;
import co.edu.unbosque.biohub.reportes.domain.ReporteArchivo;
import co.edu.unbosque.biohub.reportes.domain.SolicitudReporte;
import co.edu.unbosque.biohub.reportes.service.SolicitudesReporteService;

/**
 * Reporte mensual asíncrono (HU-09, SD-08): pedir (202), consultar el estado y descargar el CSV o el PDF.
 * Roles: los mismos de los demás reportes. Contrato: 04-contrato-api.md §16.
 */
@RestController
@RequestMapping("/api/reportes/solicitudes")
public class SolicitudesReporteController {

    private final SolicitudesReporteService servicio;
    private final ObjectMapper json;

    public SolicitudesReporteController(SolicitudesReporteService servicio, ObjectMapper json) {
        this.servicio = servicio;
        this.json = json;
    }

    public record SolicitarReporteRequest(String periodo) {
    }

    /** 202 Accepted: el reporte se genera en segundo plano y el navegador no queda bloqueado (HU-09 c.1). */
    @PostMapping
    @PreAuthorize("hasAnyRole('analista_ambiental', 'administrador')")
    public ResponseEntity<SolicitudResponse> solicitar(@RequestBody SolicitarReporteRequest peticion,
                                                       @AuthenticationPrincipal Jwt jwt) {
        YearMonth periodo;
        try {
            periodo = YearMonth.parse(peticion == null || peticion.periodo() == null ? "" : peticion.periodo().strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("El periodo debe tener el formato AAAA-MM.");
        }
        String usuario = jwt.getClaimAsString("preferred_username");
        SolicitudReporte s = servicio.solicitar(periodo, usuario != null ? usuario : jwt.getSubject(),
                OrigenSolicitud.MANUAL);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/reportes/solicitudes/" + s.getId()))
                .body(respuesta(s));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('analista_ambiental', 'administrador')")
    public List<SolicitudResponse> recientes() {
        return servicio.recientes().stream().map(this::respuesta).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('analista_ambiental', 'administrador')")
    public SolicitudResponse estado(@PathVariable UUID id) {
        return respuesta(servicio.obtener(id));
    }

    @GetMapping(value = "/{id}/csv", produces = "text/csv; charset=UTF-8")
    @PreAuthorize("hasAnyRole('analista_ambiental', 'administrador')")
    public ResponseEntity<byte[]> csv(@PathVariable UUID id) {
        ReporteArchivo a = servicio.archivos(id);
        return archivo(a.getCsv(), MediaType.parseMediaType("text/csv; charset=UTF-8"), nombre(id, "csv"));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('analista_ambiental', 'administrador')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        ReporteArchivo a = servicio.archivos(id);
        return archivo(a.getPdf(), MediaType.APPLICATION_PDF, nombre(id, "pdf"));
    }

    private String nombre(UUID id, String extension) {
        return "presion-ecosistemas-" + servicio.obtener(id).getPeriodo() + "." + extension;
    }

    private static ResponseEntity<byte[]> archivo(byte[] datos, MediaType tipo, String nombre) {
        return ResponseEntity.ok()
                .contentType(tipo)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(datos);
    }

    private SolicitudResponse respuesta(SolicitudReporte s) {
        JsonNode resumen = null;
        if (s.getResumenJson() != null) {
            try {
                resumen = json.readTree(s.getResumenJson());
            } catch (IOException e) {
                resumen = null;
            }
        }
        return new SolicitudResponse(s.getId(), s.getPeriodo(), s.getEstado().name(), s.getOrigen().name(),
                s.getSolicitadoPor(), s.getSolicitadoEn(), s.getIniciadoEn(), s.getTerminadoEn(), s.getError(), resumen);
    }

    /** {@code resumen} trae los índices por departamento y las zonas en alerta cuando el estado es LISTO. */
    public record SolicitudResponse(UUID id, String periodo, String estado, String origen, String solicitadoPor,
                                    Instant solicitadoEn, Instant iniciadoEn, Instant terminadoEn, String error,
                                    JsonNode resumen) {
    }
}
