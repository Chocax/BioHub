package co.edu.unbosque.biohub.reportes.controller;

import co.edu.unbosque.biohub.reportes.domain.HallazgoReporte;
import co.edu.unbosque.biohub.reportes.service.ConsultasReportesService;
import co.edu.unbosque.biohub.reportes.service.ConsultasReportesService.ResumenAnalitico;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reportes")
@Validated
@PreAuthorize("hasAnyRole('analista_ambiental', 'administrador')")
public class ReportesController {

    private final ConsultasReportesService service;

    public ReportesController(ConsultasReportesService service) {
        this.service = service;
    }

    @GetMapping("/resumen")
    public ResumenAnalitico resumen(
        @RequestParam(required = false) @Size(max = 80) String departamento,
        @RequestParam(required = false) @Size(max = 100) String familia,
        @RequestParam(required = false) @Size(max = 30) String estado,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.generarResumen(departamento, familia, estado, desde, hasta);
    }

    @GetMapping("/hallazgos")
    public List<HallazgoReporteResponse> hallazgos(
        @RequestParam(required = false) @Size(max = 80) String departamento,
        @RequestParam(required = false) @Size(max = 100) String familia,
        @RequestParam(required = false) @Size(max = 30) String estado,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.listarDetalle(departamento, familia, estado, desde, hasta).stream()
            .map(HallazgoReporteResponse::desde)
            .toList();
    }

    @GetMapping(value = "/exportar/csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> exportarCsv(
        @RequestParam(required = false) @Size(max = 80) String departamento,
        @RequestParam(required = false) @Size(max = 100) String familia,
        @RequestParam(required = false) @Size(max = 30) String estado,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        byte[] csv = service.exportarCsv(departamento, familia, estado, desde, hasta);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment().filename("reporte-biohub.csv").build());
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    @GetMapping(value = "/exportar/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportarPdf(
        @RequestParam(required = false) @Size(max = 80) String departamento,
        @RequestParam(required = false) @Size(max = 100) String familia,
        @RequestParam(required = false) @Size(max = 30) String estado,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        byte[] pdf = service.exportarPdf(departamento, familia, estado, desde, hasta);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename("reporte-biohub.pdf").build());
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    public record HallazgoReporteResponse(
        UUID hallazgoId,
        int version,
        String nombreCientifico,
        String nombreComun,
        String familia,
        String tipoEspecimen,
        LocalDate fechaRecoleccion,
        String estadoConservacion,
        String departamento,
        String region) {

        static HallazgoReporteResponse desde(HallazgoReporte h) {
            return new HallazgoReporteResponse(
                h.getHallazgoId(), h.getVersion(), h.getNombreCientifico(), h.getNombreComun(),
                h.getFamilia(), h.getTipoEspecimen(), h.getFechaRecoleccion(),
                h.getEstadoConservacion(), h.getDepartamento(), h.getRegion());
        }
    }
}
