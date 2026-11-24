package co.edu.unbosque.biohub.equipos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "equipo_trabajo")
public class EquipoTrabajo {

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "codigo_inventario", length = 40, nullable = false, unique = true)
    private String codigoInventario;

    @Column(name = "nombre", length = 120, nullable = false)
    private String nombre;

    @Column(name = "categoria", length = 50, nullable = false)
    private String categoria;

    @Column(name = "descripcion", length = 500)
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoEquipo estado;

    @Column(name = "custodio_id", length = 64)
    private String custodioId;

    @Column(name = "custodio_usuario", length = 100)
    private String custodioUsuario;

    @Column(name = "asignado_en")
    private Instant asignadoEn;

    @Column(name = "creado_en", nullable = false, updatable = false, insertable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false, updatable = false, insertable = false)
    private Instant actualizadoEn;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected EquipoTrabajo() {
    }

    public EquipoTrabajo(UUID id, String codigoInventario, String nombre, String categoria, String descripcion) {
        this.id = id;
        this.codigoInventario = codigoInventario;
        this.nombre = nombre;
        this.categoria = categoria;
        this.descripcion = descripcion;
        this.estado = EstadoEquipo.DISPONIBLE;
    }

    public EquipoTrabajo(UUID id, String codigoInventario, String nombre, String categoria, String descripcion,
                         String custodioId, String custodioUsuario, Instant asignadoEn) {
        this.id = id;
        this.codigoInventario = codigoInventario;
        this.nombre = nombre;
        this.categoria = categoria;
        this.descripcion = descripcion;
        this.estado = EstadoEquipo.ASIGNADO;
        this.custodioId = custodioId;
        this.custodioUsuario = custodioUsuario;
        this.asignadoEn = asignadoEn;
    }

    public void asignarACustodio(String custodioId, String custodioUsuario, Instant fecha) {
        if (this.estado == EstadoEquipo.BAJA) {
            throw new IllegalStateException("El equipo está dado de BAJA; es un estado final y no admite cambios.");
        }
        if (this.estado != EstadoEquipo.DISPONIBLE) {
            throw new IllegalStateException("El equipo no está DISPONIBLE para asignación (estado actual: " + this.estado + ").");
        }
        this.estado = EstadoEquipo.ASIGNADO;
        this.custodioId = custodioId;
        this.custodioUsuario = custodioUsuario;
        this.asignadoEn = fecha;
    }

    public void liberar(EstadoEquipo nuevoEstado) {
        if (this.estado == EstadoEquipo.BAJA) {
            throw new IllegalStateException("El equipo está dado de BAJA; es un estado final y no admite cambios.");
        }
        if (nuevoEstado == EstadoEquipo.ASIGNADO) {
            throw new IllegalArgumentException("Use asignarACustodio para el estado ASIGNADO");
        }
        this.estado = nuevoEstado;
        this.custodioId = null;
        this.custodioUsuario = null;
        this.asignadoEn = null;
    }

    public UUID getId() { return id; }
    public String getCodigoInventario() { return codigoInventario; }
    public String getNombre() { return nombre; }
    public String getCategoria() { return categoria; }
    public String getDescripcion() { return descripcion; }
    public EstadoEquipo getEstado() { return estado; }
    public String getCustodioId() { return custodioId; }
    public String getCustodioUsuario() { return custodioUsuario; }
    public Instant getAsignadoEn() { return asignadoEn; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public Long getVersion() { return version; }
}
