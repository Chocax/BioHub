package co.edu.unbosque.biohub.equipos.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistorialCustodiaEquipoRepository extends JpaRepository<HistorialCustodiaEquipo, UUID> {
    List<HistorialCustodiaEquipo> findByEquipoIdOrderByRegistradoEnDesc(UUID equipoId);
}
