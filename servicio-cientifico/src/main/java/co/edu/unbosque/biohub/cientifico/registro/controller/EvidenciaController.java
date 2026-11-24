package co.edu.unbosque.biohub.cientifico.registro.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo;
import co.edu.unbosque.biohub.cientifico.registro.service.EvidenciaService;

/**
 * Fotografías y audios de un hallazgo (RF1, HU-01 c.2). Roles: los mismos que registran (subir) y los que consultan
 * el hallazgo (ver y descargar). 04-contrato-api.md §13.9.
 */
@RestController
@RequestMapping("/api/cientifico/hallazgos/{id}/evidencias")
public class EvidenciaController {

    private final EvidenciaService evidencias;

    public EvidenciaController(EvidenciaService evidencias) {
        this.evidencias = evidencias;
    }

    /** {@code multipart/form-data} con el campo {@code archivo}. 201 si es nueva; 200 si el mismo archivo ya estaba. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango')")
    public ResponseEntity<EvidenciaResponse> subir(@PathVariable UUID id, @RequestPart("archivo") MultipartFile archivo,
                                                   @AuthenticationPrincipal Jwt jwt) {
        byte[] contenido;
        try {
            contenido = archivo.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EvidenciaService.Subida subida = evidencias.subir(id, archivo.getOriginalFilename(), contenido,
                HallazgoController.usuario(jwt));
        EvidenciaResponse cuerpo = EvidenciaResponse.de(subida.evidencia());
        if (!subida.nueva()) {
            return ResponseEntity.ok(cuerpo);
        }
        return ResponseEntity.created(URI.create("/api/cientifico/hallazgos/" + id + "/evidencias/" + cuerpo.id()))
                .body(cuerpo);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango', 'auditor_cientifico', 'administrador')")
    public List<EvidenciaResponse> listar(@PathVariable UUID id) {
        return evidencias.listar(id).stream().map(EvidenciaResponse::de).toList();
    }

    /** Descarga el archivo. Siempre como adjunto y con {@code nosniff}: el navegador no lo ejecuta como página. */
    @GetMapping("/{evidenciaId}")
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango', 'auditor_cientifico', 'administrador')")
    public ResponseEntity<InputStreamResource> descargar(@PathVariable UUID id, @PathVariable UUID evidenciaId) {
        EvidenciaHallazgo evidencia = evidencias.obtener(id, evidenciaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(evidencia.getContentType()))
                .contentLength(evidencia.getTamanoBytes())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, adjunto(evidencia.getNombre()))
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(evidencias.abrir(evidencia)));
    }

    /** Muestra u oculta la fotografía en el portal público (D65). Cuerpo: {@code {"publica": true}}. */
    @PutMapping("/{evidenciaId}/publica")
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango')")
    public EvidenciaResponse marcarPublica(@PathVariable UUID id, @PathVariable UUID evidenciaId,
                                           @RequestBody MarcarPublicaRequest cuerpo, @AuthenticationPrincipal Jwt jwt) {
        return EvidenciaResponse.de(evidencias.marcarPublica(id, evidenciaId, Boolean.TRUE.equals(cuerpo.publica()),
                HallazgoController.usuario(jwt)));
    }

    public record MarcarPublicaRequest(Boolean publica) {
    }

    /** {@code filename} simple si el nombre es ASCII; si trae tildes, además {@code filename*} en UTF-8 (RFC 6266). */
    static String adjunto(String nombre) {
        boolean ascii = nombre.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
        ContentDisposition.Builder adjunto = ContentDisposition.attachment();
        return (ascii ? adjunto.filename(nombre) : adjunto.filename(nombre, java.nio.charset.StandardCharsets.UTF_8))
                .build().toString();
    }

    /** Metadatos de la evidencia; nunca la ruta interna en disco. */
    public record EvidenciaResponse(UUID id, String nombre, String tipo, String contentType, long tamanoBytes,
                                    String sha256, String subidoPor, boolean publica, Instant creadoEn) {
        static EvidenciaResponse de(EvidenciaHallazgo e) {
            return new EvidenciaResponse(e.getId(), e.getNombre(), e.getTipo().name(), e.getContentType(),
                    e.getTamanoBytes(), e.getSha256(), e.getSubidoPor(), e.isPublica(), e.getCreadoEn());
        }
    }
}
