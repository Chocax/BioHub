package co.edu.unbosque.biohub.reportes.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HallazgoReporteRepository extends JpaRepository<HallazgoReporte, UUID> {

    @Query("""
            SELECT h FROM HallazgoReporte h
            WHERE (:departamento IS NULL OR LOWER(h.departamento) = LOWER(:departamento))
              AND (:familia IS NULL OR LOWER(h.familia) = LOWER(:familia))
              AND (:estado IS NULL OR UPPER(h.estadoConservacion) = UPPER(:estado))
              AND (:desde IS NULL OR h.fechaRecoleccion >= :desde)
              AND (:hasta IS NULL OR h.fechaRecoleccion <= :hasta)
            ORDER BY h.fechaRecoleccion DESC, h.nombreCientifico ASC
            """)
    List<HallazgoReporte> buscarFiltrado(
        @Param("departamento") String departamento,
        @Param("familia") String familia,
        @Param("estado") String estado,
        @Param("desde") LocalDate desde,
        @Param("hasta") LocalDate hasta);

    /**
     * Cruce masivo por departamento (RF16): la base analítica agrega en SQL, así que el reporte no carga las filas
     * en memoria aunque haya millones.
     */
    @Query("""
            SELECT new co.edu.unbosque.biohub.reportes.domain.ConteoDepartamento(
                h.departamento, COUNT(h),
                SUM(CASE WHEN UPPER(h.estadoConservacion) = 'EN_PELIGRO' THEN 1 ELSE 0 END),
                COUNT(DISTINCT LOWER(h.nombreCientifico)))
            FROM HallazgoReporte h
            WHERE h.fechaRecoleccion >= :desde AND h.fechaRecoleccion <= :hasta
            GROUP BY h.departamento
            """)
    List<ConteoDepartamento> contarPorDepartamento(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);
}
