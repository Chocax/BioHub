package co.edu.unbosque.biohub.reportes.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReporteArchivoRepository extends JpaRepository<ReporteArchivo, UUID> {
}
