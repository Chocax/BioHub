package co.edu.unbosque.biohub.equipos.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipoTrabajoRepository extends JpaRepository<EquipoTrabajo, UUID> {
    boolean existsByCodigoInventarioIgnoreCase(String codigoInventario);
    Page<EquipoTrabajo> findByEstado(EstadoEquipo estado, Pageable pageable);
    Page<EquipoTrabajo> findByCustodioId(String custodioId, Pageable pageable);
    Page<EquipoTrabajo> findByEstadoAndCustodioId(EstadoEquipo estado, String custodioId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EquipoTrabajo e WHERE e.id = :id")
    Optional<EquipoTrabajo> findByIdConBloqueo(@Param("id") UUID id);
}
