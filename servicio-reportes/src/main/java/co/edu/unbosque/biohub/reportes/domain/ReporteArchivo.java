package co.edu.unbosque.biohub.reportes.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** CSV y PDF de un reporte terminado (HU-09 c.3). Tabla aparte para que listar solicitudes no los cargue. */
@Entity
@Table(name = "reporte_archivo")
public class ReporteArchivo {

    @Id
    @Column(name = "solicitud_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID solicitudId;

    @Column(name = "csv", nullable = false, columnDefinition = "mediumblob")
    private byte[] csv;

    @Column(name = "pdf", nullable = false, columnDefinition = "mediumblob")
    private byte[] pdf;

    protected ReporteArchivo() {
    }

    public ReporteArchivo(UUID solicitudId, byte[] csv, byte[] pdf) {
        this.solicitudId = solicitudId;
        this.csv = csv;
        this.pdf = pdf;
    }

    public UUID getSolicitudId() {
        return solicitudId;
    }

    public byte[] getCsv() {
        return csv;
    }

    public byte[] getPdf() {
        return pdf;
    }
}
