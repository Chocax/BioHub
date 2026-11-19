package co.edu.unbosque.biohub.cientifico.registro.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Evidencias (fotografías y audios) de un hallazgo (HU-01). */
public interface EvidenciaHallazgoRepository extends JpaRepository<EvidenciaHallazgo, UUID> {

    List<EvidenciaHallazgo> findByHallazgoIdOrderByCreadoEnAsc(UUID hallazgoId);

    boolean existsByHallazgoIdAndSha256(UUID hallazgoId, String sha256);

    Optional<EvidenciaHallazgo> findByHallazgoIdAndSha256(UUID hallazgoId, String sha256);

    /** La foto pública más reciente de una especie (D65), entre sus hallazgos vigentes. */
    @Query("""
            SELECT e FROM EvidenciaHallazgo e, Hallazgo h
            WHERE h.id = e.hallazgoId AND h.nombreCientifico = :nombre AND h.anuladoEn IS NULL
              AND e.publica = true AND e.tipo = co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo.TipoEvidencia.FOTOGRAFIA
            ORDER BY e.creadoEn DESC""")
    List<EvidenciaHallazgo> fotosPublicasDeEspecie(@Param("nombre") String nombreCientifico, Pageable pagina);
}
