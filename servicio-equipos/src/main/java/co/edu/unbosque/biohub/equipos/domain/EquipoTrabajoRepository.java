package co.edu.unbosque.biohub.equipos.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipoTrabajoRepository extends JpaRepository<EquipoTrabajo, UUID> {
    boolean existsByCodigoInventarioIgnoreCase(String codigoInventario);
    Page<EquipoTrabajo> findByEstado(EstadoEquipo estado, Pageable pageable);
    Page<EquipoTrabajo> findByCustodioId(String custodioId, Pageable pageable);
}
