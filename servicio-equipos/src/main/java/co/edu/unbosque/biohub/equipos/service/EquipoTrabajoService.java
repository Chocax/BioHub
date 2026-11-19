package co.edu.unbosque.biohub.equipos.service;

import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajo;
import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajoRepository;
import co.edu.unbosque.biohub.equipos.domain.EstadoEquipo;
import co.edu.unbosque.biohub.equipos.domain.ValidadorCustodioPort;
import co.edu.unbosque.biohub.equipos.infrastructure.GatewayCustodioAdapter.CustodioInvalidoException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquipoTrabajoService {

    private final EquipoTrabajoRepository repository;
    private final ValidadorCustodioPort validadorCustodio;

    public EquipoTrabajoService(EquipoTrabajoRepository repository, ValidadorCustodioPort validadorCustodio) {
        this.repository = repository;
        this.validadorCustodio = validadorCustodio;
    }

    @Transactional
    public EquipoTrabajo registrar(String codigoInventario, String nombre, String categoria, String descripcion) {
        String codigoLimpio = codigoInventario.trim().toUpperCase();
        if (repository.existsByCodigoInventarioIgnoreCase(codigoLimpio)) {
            throw new ConflictoEquipoException("Ya existe un equipo con el código de inventario: " + codigoLimpio);
        }
        EquipoTrabajo equipo = new EquipoTrabajo(
            UUID.randomUUID(),
            codigoLimpio,
            nombre.trim(),
            categoria.trim(),
            descripcion != null ? descripcion.trim() : null
        );
        return repository.save(equipo);
    }

    @Transactional
    public EquipoTrabajo asignar(UUID equipoId, String custodioId, String bearerToken) {
        EquipoTrabajo equipo = obtenerPorId(equipoId);
        if (equipo.getEstado() == EstadoEquipo.BAJA || equipo.getEstado() == EstadoEquipo.MANTENIMIENTO) {
            throw new ConflictoEquipoException("No se puede asignar un equipo en estado " + equipo.getEstado());
        }

        var custodio = validadorCustodio.validarCustodioActivo(custodioId.trim(), bearerToken);
        if (!custodio.activo()) {
            throw new CustodioInvalidoException("El custodio seleccionado está inactivo y no puede recibir equipos.");
        }
        if (!custodio.esInvestigador()) {
            throw new CustodioInvalidoException("El custodio debe ser un investigador de campo.");
        }

        equipo.asignarACustodio(custodio.id(), custodio.usuario(), Instant.now());
        return repository.save(equipo);
    }

    @Transactional
    public EquipoTrabajo devolverOCambiarEstado(UUID equipoId, EstadoEquipo nuevoEstado) {
        if (nuevoEstado == EstadoEquipo.ASIGNADO) {
            throw new IllegalArgumentException("Para asignar un equipo use el endpoint de asignación.");
        }
        EquipoTrabajo equipo = obtenerPorId(equipoId);
        equipo.liberar(nuevoEstado);
        return repository.save(equipo);
    }

    @Transactional(readOnly = true)
    public EquipoTrabajo obtenerPorId(UUID equipoId) {
        return repository.findById(equipoId)
            .orElseThrow(() -> new EquipoNoEncontradoException(equipoId));
    }

    @Transactional(readOnly = true)
    public Page<EquipoTrabajo> listar(EstadoEquipo estado, String custodioId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) (page + 1) * size > 10_000) {
            throw new IllegalArgumentException("Parámetros de paginación fuera de rango.");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "nombre", "id"));
        boolean conCustodio = custodioId != null && !custodioId.isBlank();
        if (estado != null && conCustodio) {
            return repository.findByEstadoAndCustodioId(estado, custodioId.trim(), pageable);
        }
        if (estado != null) {
            return repository.findByEstado(estado, pageable);
        }
        if (conCustodio) {
            return repository.findByCustodioId(custodioId.trim(), pageable);
        }
        return repository.findAll(pageable);
    }

    public static class EquipoNoEncontradoException extends RuntimeException {
        public EquipoNoEncontradoException(UUID id) { super("No existe el equipo con id: " + id); }
    }

    public static class ConflictoEquipoException extends RuntimeException {
        public ConflictoEquipoException(String message) { super(message); }
    }
}
