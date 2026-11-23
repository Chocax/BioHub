package co.edu.unbosque.biohub.equipos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "historial_custodia_equipo")
public class HistorialCustodiaEquipo {

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "equipo_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID equipoId;

    @Column(name = "accion", length = 30, nullable = false, updatable = false)
    private String accion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_anterior", length = 20, updatable = false)
    private EstadoEquipo estadoAnterior;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_nuevo", length = 20, nullable = false, updatable = false)
    private EstadoEquipo estadoNuevo;

    @Column(name = "custodio_id", length = 64, updatable = false)
    private String custodioId;

    @Column(name = "custodio_usuario", length = 100, updatable = false)
    private String custodioUsuario;

    @Column(name = "observacion", length = 300, updatable = false)
    private String observacion;

    @Column(name = "registrado_en", nullable = false, updatable = false)
    private Instant registradoEn;

    protected HistorialCustodiaEquipo() {
    }

    public HistorialCustodiaEquipo(UUID equipoId, String accion, EstadoEquipo estadoAnterior,
                                   EstadoEquipo estadoNuevo, String custodioId,
                                   String custodioUsuario, String observacion, Instant registradoEn) {
        this.id = UUID.randomUUID();
        this.equipoId = equipoId;
        this.accion = accion;
        this.estadoAnterior = estadoAnterior;
        this.estadoNuevo = estadoNuevo;
        this.custodioId = custodioId;
        this.custodioUsuario = custodioUsuario;
        this.observacion = observacion;
        this.registradoEn = registradoEn;
    }

    public UUID getId() { return id; }
    public UUID getEquipoId() { return equipoId; }
    public String getAccion() { return accion; }
    public EstadoEquipo getEstadoAnterior() { return estadoAnterior; }
    public EstadoEquipo getEstadoNuevo() { return estadoNuevo; }
    public String getCustodioId() { return custodioId; }
    public String getCustodioUsuario() { return custodioUsuario; }
    public String getObservacion() { return observacion; }
    public Instant getRegistradoEn() { return registradoEn; }
}
