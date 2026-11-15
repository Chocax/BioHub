package co.edu.unbosque.biohub.reportes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "hallazgo_reporte")
public class HallazgoReporte {

    @Id
    @Column(name = "hallazgo_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID hallazgoId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "nombre_cientifico", length = 150, nullable = false)
    private String nombreCientifico;

    @Column(name = "nombre_comun", length = 150)
    private String nombreComun;

    @Column(name = "familia", length = 100, nullable = false)
    private String familia;

    @Column(name = "tipo_especimen", length = 60)
    private String tipoEspecimen;

    @Column(name = "fecha_recoleccion", nullable = false)
    private LocalDate fechaRecoleccion;

    @Column(name = "estado_conservacion", length = 30, nullable = false)
    private String estadoConservacion;

    @Column(name = "departamento", length = 80, nullable = false)
    private String departamento;

    @Column(name = "region", length = 80)
    private String region;

    @Column(name = "actualizado_en", nullable = false, insertable = false, updatable = false)
    private Instant actualizadoEn;

    protected HallazgoReporte() {
    }

    public HallazgoReporte(UUID hallazgoId, int version, String nombreCientifico, String nombreComun,
                           String familia, String tipoEspecimen, LocalDate fechaRecoleccion,
                           String estadoConservacion, String departamento, String region) {
        this.hallazgoId = hallazgoId;
        this.version = version;
        this.nombreCientifico = nombreCientifico;
        this.nombreComun = nombreComun;
        this.familia = familia;
        this.tipoEspecimen = tipoEspecimen;
        this.fechaRecoleccion = fechaRecoleccion;
        this.estadoConservacion = estadoConservacion;
        this.departamento = departamento;
        this.region = region;
    }

    public void actualizarDesdeSnapshot(int nuevaVersion, String nombreCientifico, String nombreComun,
                                        String familia, String tipoEspecimen, LocalDate fechaRecoleccion,
                                        String estadoConservacion, String departamento, String region) {
        if (nuevaVersion <= this.version) {
            return;
        }
        this.version = nuevaVersion;
        this.nombreCientifico = nombreCientifico;
        this.nombreComun = nombreComun;
        this.familia = familia;
        this.tipoEspecimen = tipoEspecimen;
        this.fechaRecoleccion = fechaRecoleccion;
        this.estadoConservacion = estadoConservacion;
        this.departamento = departamento;
        this.region = region;
    }

    public UUID getHallazgoId() { return hallazgoId; }
    public int getVersion() { return version; }
    public String getNombreCientifico() { return nombreCientifico; }
    public String getNombreComun() { return nombreComun; }
    public String getFamilia() { return familia; }
    public String getTipoEspecimen() { return tipoEspecimen; }
    public LocalDate getFechaRecoleccion() { return fechaRecoleccion; }
    public String getEstadoConservacion() { return estadoConservacion; }
    public String getDepartamento() { return departamento; }
    public String getRegion() { return region; }
    public Instant getActualizadoEn() { return actualizadoEn; }
}
