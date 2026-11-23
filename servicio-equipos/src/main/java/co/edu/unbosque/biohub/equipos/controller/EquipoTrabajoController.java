package co.edu.unbosque.biohub.equipos.controller;

import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajo;
import co.edu.unbosque.biohub.equipos.domain.EstadoEquipo;
import co.edu.unbosque.biohub.equipos.domain.HistorialCustodiaEquipo;
import co.edu.unbosque.biohub.equipos.service.EquipoTrabajoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipos")
@PreAuthorize("hasRole('administrador')")
public class EquipoTrabajoController {

    private final EquipoTrabajoService service;

    public EquipoTrabajoController(EquipoTrabajoService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EquipoResponse> registrar(
        @Valid @RequestBody RegistrarEquipoRequest request,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        EquipoTrabajo creado = service.registrar(
            request.codigoInventario(),
            request.nombre(),
            request.categoria(),
            request.descripcion(),
            request.custodioId(),
            authorization
        );
        return ResponseEntity
            .created(URI.create("/api/equipos/" + creado.getId()))
            .body(EquipoResponse.desde(creado));
    }

    @GetMapping
    public PaginaEquiposResponse listar(
        @RequestParam(required = false) EstadoEquipo estado,
        @RequestParam(required = false) String custodioId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        Page<EquipoTrabajo> resultado = service.listar(estado, custodioId, page, size);
        return new PaginaEquiposResponse(
            resultado.getContent().stream().map(EquipoResponse::desde).toList(),
            resultado.getNumber(),
            resultado.getSize(),
            resultado.getTotalElements(),
            resultado.getTotalPages()
        );
    }

    @GetMapping("/{id}")
    public EquipoResponse obtener(@PathVariable UUID id) {
        return EquipoResponse.desde(service.obtenerPorId(id));
    }

    @GetMapping("/{id}/historial")
    public List<HistorialCustodiaResponse> historial(@PathVariable UUID id) {
        return service.listarHistorial(id).stream()
            .map(HistorialCustodiaResponse::desde)
            .toList();
    }

    @PutMapping("/{id}/asignacion")
    public EquipoResponse asignar(
        @PathVariable UUID id,
        @Valid @RequestBody AsignarEquipoRequest request,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return EquipoResponse.desde(service.asignar(id, request.custodioId(), authorization));
    }

    @PutMapping("/{id}/estado")
    public EquipoResponse cambiarEstado(
        @PathVariable UUID id,
        @Valid @RequestBody CambiarEstadoRequest request) {
        return EquipoResponse.desde(service.devolverOCambiarEstado(id, request.estado()));
    }

    public record RegistrarEquipoRequest(
        @NotBlank @Size(max = 40) String codigoInventario,
        @NotBlank @Size(max = 120) String nombre,
        @NotBlank @Size(max = 50) String categoria,
        @Size(max = 500) String descripcion,
        @NotBlank @Size(max = 64) String custodioId) {}

    public record AsignarEquipoRequest(@NotBlank @Size(max = 64) String custodioId) {}

    public record CambiarEstadoRequest(@NotNull EstadoEquipo estado) {}

    public record EquipoResponse(
        UUID id,
        String codigoInventario,
        String nombre,
        String categoria,
        String descripcion,
        EstadoEquipo estado,
        String custodioId,
        String custodioUsuario,
        Instant asignadoEn,
        Instant creadoEn,
        Instant actualizadoEn) {

        static EquipoResponse desde(EquipoTrabajo e) {
            return new EquipoResponse(
                e.getId(), e.getCodigoInventario(), e.getNombre(), e.getCategoria(),
                e.getDescripcion(), e.getEstado(), e.getCustodioId(), e.getCustodioUsuario(),
                e.getAsignadoEn(), e.getCreadoEn(), e.getActualizadoEn());
        }
    }

    public record HistorialCustodiaResponse(
        UUID id,
        UUID equipoId,
        String accion,
        EstadoEquipo estadoAnterior,
        EstadoEquipo estadoNuevo,
        String custodioId,
        String custodioUsuario,
        String observacion,
        Instant registradoEn) {

        static HistorialCustodiaResponse desde(HistorialCustodiaEquipo h) {
            return new HistorialCustodiaResponse(
                h.getId(), h.getEquipoId(), h.getAccion(), h.getEstadoAnterior(),
                h.getEstadoNuevo(), h.getCustodioId(), h.getCustodioUsuario(),
                h.getObservacion(), h.getRegistradoEn());
        }
    }

    public record PaginaEquiposResponse(
        List<EquipoResponse> content,
        int page,
        int size,
        long total,
        int totalPaginas) {}
}
