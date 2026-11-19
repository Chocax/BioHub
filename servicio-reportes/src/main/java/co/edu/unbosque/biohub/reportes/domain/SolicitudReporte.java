package co.edu.unbosque.biohub.reportes.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Una solicitud de reporte mensual y su estado (HU-09 c.1, SD-08). Los archivos van en {@link ReporteArchivo}. */
@Entity
@Table(name = "solicitud_reporte")
public class SolicitudReporte {

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "periodo", length = 7, nullable = false, updatable = false, columnDefinition = "char(7)")
    private String periodo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 12, nullable = false)
    private EstadoSolicitud estado;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", length = 12, nullable = false, updatable = false)
    private OrigenSolicitud origen;

    @Column(name = "solicitado_por", length = 100, nullable = false, updatable = false)
    private String solicitadoPor;

    @Column(name = "solicitado_en", nullable = false, updatable = false)
    private Instant solicitadoEn;

    @Column(name = "iniciado_en")
    private Instant iniciadoEn;

    @Column(name = "terminado_en")
    private Instant terminadoEn;

    @Column(name = "error", length = 300)
    private String error;

    @Column(name = "resumen_json", columnDefinition = "json")
    private String resumenJson;

    protected SolicitudReporte() {
    }

    public SolicitudReporte(String periodo, OrigenSolicitud origen, String solicitadoPor, Instant ahora) {
        this.id = UUID.randomUUID();
        this.periodo = periodo;
        this.origen = origen;
        this.solicitadoPor = solicitadoPor;
        this.solicitadoEn = ahora;
        this.estado = EstadoSolicitud.PENDIENTE;
    }

    /** Solo una solicitud pendiente se puede empezar: protege contra un mensaje entregado dos veces. */
    public boolean iniciar(Instant ahora) {
        if (estado != EstadoSolicitud.PENDIENTE) {
            return false;
        }
        estado = EstadoSolicitud.EN_PROCESO;
        iniciadoEn = ahora;
        return true;
    }

    public void terminar(String resumenJson, Instant ahora) {
        this.resumenJson = resumenJson;
        this.estado = EstadoSolicitud.LISTO;
        this.terminadoEn = ahora;
    }

    public void fallar(String motivo, Instant ahora) {
        this.error = motivo == null ? null : motivo.substring(0, Math.min(300, motivo.length()));
        this.estado = EstadoSolicitud.FALLIDO;
        this.terminadoEn = ahora;
    }

    public UUID getId() {
        return id;
    }

    public String getPeriodo() {
        return periodo;
    }

    public EstadoSolicitud getEstado() {
        return estado;
    }

    public OrigenSolicitud getOrigen() {
        return origen;
    }

    public String getSolicitadoPor() {
        return solicitadoPor;
    }

    public Instant getSolicitadoEn() {
        return solicitadoEn;
    }

    public Instant getIniciadoEn() {
        return iniciadoEn;
    }

    public Instant getTerminadoEn() {
        return terminadoEn;
    }

    public String getError() {
        return error;
    }

    public String getResumenJson() {
        return resumenJson;
    }
}
