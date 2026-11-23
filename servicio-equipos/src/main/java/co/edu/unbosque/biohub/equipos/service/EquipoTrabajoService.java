package co.edu.unbosque.biohub.equipos.service;

import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajo;
import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajoRepository;
import co.edu.unbosque.biohub.equipos.domain.EstadoEquipo;
import co.edu.unbosque.biohub.equipos.domain.HistorialCustodiaEquipo;
import co.edu.unbosque.biohub.equipos.domain.HistorialCustodiaEquipoRepository;
import co.edu.unbosque.biohub.equipos.domain.ValidadorCustodioPort;
import co.edu.unbosque.biohub.equipos.infrastructure.GatewayCustodioAdapter.CustodioInvalidoException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquipoTrabajoService {

    private final EquipoTrabajoRepository repository;
    private final HistorialCustodiaEquipoRepository historialRepository;
    private final ValidadorCustodioPort validadorCustodio;

    public EquipoTrabajoService(EquipoTrabajoRepository repository,
                                HistorialCustodiaEquipoRepository historialRepository,
                                ValidadorCustodioPort validadorCustodio) {
        this.repository = repository;
        this.historialRepository = historialRepository;
        this.validadorCustodio = validadorCustodio;
    }

    @Transactional
    public EquipoTrabajo registrar(String codigoInventario, String nombre, String categoria,
                                   String descripcion, String custodioId, String bearerToken) {
        if (custodioId == null || custodioId.isBlank()) {
            throw new CustodioInvalidoException("El custodio inicial es obligatorio para dar de alta el equipo.");
        }
        String codigoLimpio = codigoInventario.trim().toUpperCase();
        if (repository.existsByCodigoInventarioIgnoreCase(codigoLimpio)) {
            throw new ConflictoEquipoException("Ya existe un equipo con el código de inventario: " + codigoLimpio);
        }

        var custodio = validarCustodio(custodioId, bearerToken);
        Instant ahora = Instant.now();
        EquipoTrabajo equipo = new EquipoTrabajo(
            UUID.randomUUID(),
            codigoLimpio,
            nombre.trim(),
            categoria.trim(),
            descripcion != null ? descripcion.trim() : null,
            custodio.id(),
            custodio.usuario(),
            ahora
        );
        EquipoTrabajo guardado = repository.save(equipo);
        historialRepository.save(new HistorialCustodiaEquipo(
            guardado.getId(),
            "ALTA_ASIGNADA",
            null,
            EstadoEquipo.ASIGNADO,
            custodio.id(),
            custodio.usuario(),
            "Alta de equipo con custodio inicial obligatorio",
            ahora
        ));
        return guardado;
    }

    @Transactional
    public EquipoTrabajo asignar(UUID equipoId, String custodioId, String bearerToken) {
        EquipoTrabajo equipo = repository.findByIdConBloqueo(equipoId)
            .orElseThrow(() -> new EquipoNoEncontradoException(equipoId));

        if (equipo.getEstado() == EstadoEquipo.BAJA) {
            throw new ConflictoEquipoException("El equipo está dado de BAJA; es un estado final y no admite cambios.");
        }
        if (equipo.getEstado() != EstadoEquipo.DISPONIBLE) {
            throw new ConflictoEquipoException("El equipo ya se encuentra en estado " + equipo.getEstado() + " y no admite asignación simultánea.");
        }

        var custodio = validarCustodio(custodioId, bearerToken);
        EstadoEquipo estadoAnterior = equipo.getEstado();
        Instant ahora = Instant.now();
        equipo.asignarACustodio(custodio.id(), custodio.usuario(), ahora);
        EquipoTrabajo actualizado = repository.save(equipo);

        historialRepository.save(new HistorialCustodiaEquipo(
            actualizado.getId(),
            "ASIGNACION",
            estadoAnterior,
            EstadoEquipo.ASIGNADO,
            custodio.id(),
            custodio.usuario(),
            "Asignación de equipo a investigador",
            ahora
        ));
        return actualizado;
    }

    @Transactional
    public EquipoTrabajo devolverOCambiarEstado(UUID equipoId, EstadoEquipo nuevoEstado) {
        if (nuevoEstado == EstadoEquipo.ASIGNADO) {
            throw new IllegalArgumentException("Para asignar un equipo use el endpoint de asignación.");
        }
        EquipoTrabajo equipo = repository.findByIdConBloqueo(equipoId)
            .orElseThrow(() -> new EquipoNoEncontradoException(equipoId));

        if (equipo.getEstado() == EstadoEquipo.BAJA) {
            throw new ConflictoEquipoException("El equipo ya se encuentra en estado BAJA (estado final irreversible).");
        }

        EstadoEquipo estadoAnterior = equipo.getEstado();
        String custodioAnteriorId = equipo.getCustodioId();
        String custodioAnteriorUsuario = equipo.getCustodioUsuario();

        equipo.liberar(nuevoEstado);
        EquipoTrabajo actualizado = repository.save(equipo);

        historialRepository.save(new HistorialCustodiaEquipo(
            actualizado.getId(),
            nuevoEstado == EstadoEquipo.BAJA ? "BAJA_DEFINITIVA" : "CAMBIO_ESTADO",
            estadoAnterior,
            nuevoEstado,
            custodioAnteriorId,
            custodioAnteriorUsuario,
            nuevoEstado == EstadoEquipo.BAJA ? "Equipo dado de baja de forma definitiva" : "Cambio de estado operativo a " + nuevoEstado,
            Instant.now()
        ));
        return actualizado;
    }

    @Transactional(readOnly = true)
    public EquipoTrabajo obtenerPorId(UUID equipoId) {
        return repository.findById(equipoId)
            .orElseThrow(() -> new EquipoNoEncontradoException(equipoId));
    }

    @Transactional(readOnly = true)
    public List<HistorialCustodiaEquipo> listarHistorial(UUID equipoId) {
        obtenerPorId(equipoId);
        return historialRepository.findByEquipoIdOrderByRegistradoEnDesc(equipoId);
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

    private ValidadorCustodioPort.CustodioValidado validarCustodio(String custodioId, String bearerToken) {
        var custodio = validadorCustodio.validarCustodioActivo(custodioId.trim(), bearerToken);
        if (!custodio.activo()) {
            throw new CustodioInvalidoException("El custodio seleccionado está inactivo y no puede recibir equipos.");
        }
        if (!custodio.esInvestigador()) {
            throw new CustodioInvalidoException("El custodio debe ser un investigador de campo.");
        }
        return custodio;
    }

    public static class EquipoNoEncontradoException extends RuntimeException {
        public EquipoNoEncontradoException(UUID id) { super("No existe el equipo con id: " + id); }
    }

    public static class ConflictoEquipoException extends RuntimeException {
        public ConflictoEquipoException(String message) { super(message); }
    }
}
