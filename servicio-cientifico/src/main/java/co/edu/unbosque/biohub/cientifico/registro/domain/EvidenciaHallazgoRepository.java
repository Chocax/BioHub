package co.edu.unbosque.biohub.cientifico.registro.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Evidencias (fotografías y audios) de un hallazgo (HU-01). */
public interface EvidenciaHallazgoRepository extends JpaRepository<EvidenciaHallazgo, UUID> {

    List<EvidenciaHallazgo> findByHallazgoIdOrderByCreadoEnAsc(UUID hallazgoId);

    boolean existsByHallazgoIdAndSha256(UUID hallazgoId, String sha256);

    Optional<EvidenciaHallazgo> findByHallazgoIdAndSha256(UUID hallazgoId, String sha256);

    /** Ids de las fotografías marcadas como públicas (D66): para republicarlas. Solo ids, uno a uno. */
    @Query("""
            SELECT e.id FROM EvidenciaHallazgo e
            WHERE e.publica = true AND e.tipo = co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo.TipoEvidencia.FOTOGRAFIA
            ORDER BY e.creadoEn""")
    List<UUID> idsFotosPublicas();

    /** Fotos públicas que aún no tienen miniatura (las marcadas antes de D66): se migran al arrancar. */
    @Query("""
            SELECT e.id FROM EvidenciaHallazgo e
            WHERE e.publica = true AND e.rutaMiniatura IS NULL
              AND e.tipo = co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo.TipoEvidencia.FOTOGRAFIA
            ORDER BY e.creadoEn""")
    List<UUID> idsFotosPublicasSinMiniatura();
}
