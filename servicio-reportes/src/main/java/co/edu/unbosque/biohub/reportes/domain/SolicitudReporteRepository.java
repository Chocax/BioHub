package co.edu.unbosque.biohub.reportes.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SolicitudReporteRepository extends JpaRepository<SolicitudReporte, UUID> {

    List<SolicitudReporte> findTop20ByOrderBySolicitadoEnDesc();

    boolean existsByPeriodoAndOrigen(String periodo, OrigenSolicitud origen);

    List<SolicitudReporte> findByEstadoInAndSolicitadoEnBefore(Collection<EstadoSolicitud> estados, Instant limite);
}
