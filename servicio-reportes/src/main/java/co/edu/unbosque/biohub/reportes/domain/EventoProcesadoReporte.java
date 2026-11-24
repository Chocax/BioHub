package co.edu.unbosque.biohub.reportes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evento_procesado_reporte")
public class EventoProcesadoReporte {

    @Id
    @Column(name = "evento_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID eventoId;

    @Column(name = "tipo", length = 80, nullable = false, updatable = false)
    private String tipo;

    @Column(name = "procesado_en", nullable = false, insertable = false, updatable = false)
    private Instant procesadoEn;

    protected EventoProcesadoReporte() {
    }

    public EventoProcesadoReporte(UUID eventoId, String tipo) {
        this.eventoId = eventoId;
        this.tipo = tipo;
    }

    public UUID getEventoId() { return eventoId; }
    public String getTipo() { return tipo; }
    public Instant getProcesadoEn() { return procesadoEn; }
}
