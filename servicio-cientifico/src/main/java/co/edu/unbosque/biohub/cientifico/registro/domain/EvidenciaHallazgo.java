package co.edu.unbosque.biohub.cientifico.registro.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fotografía o audio asociado a un hallazgo (HU-01). Solo se guardan los metadatos y la ruta; el archivo
 * vive en el volumen de archivos del servicio.
 *
 * <p>El {@code sha256} identifica el contenido: subir dos veces el mismo archivo al mismo hallazgo no
 * crea una evidencia duplicada (UNIQUE en la migración).
 */
@Entity
@Table(name = "hallazgo_evidencia")
public class EvidenciaHallazgo {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "binary(16)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "hallazgo_id", nullable = false, columnDefinition = "binary(16)")
    private UUID hallazgoId;

    @Column(name = "nombre", nullable = false, length = 255)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 20)
    private TipoEvidencia tipo;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "tamano_bytes", nullable = false)
    private long tamanoBytes;

    @Column(name = "ruta", nullable = false, length = 500)
    private String ruta;

    // CHAR(64) y no VARCHAR: el hash hexadecimal siempre mide lo mismo. Se declara el tipo para que
    // la validacion de esquema de Hibernate coincida con la migracion.
    @Column(name = "sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String sha256;

    /** Usuario que subió el archivo (HU-11 c.3), con el mismo identificador que el autor del historial (D59). */
    @Column(name = "subido_por", length = 100)
    private String subidoPor;

    /** Si el portal público puede mostrarla (D65). Solo fotografías; por defecto, no. */
    @Column(name = "publica", nullable = false)
    private boolean publica;

    /** Miniatura JPEG para el portal (D66): se genera una sola vez al hacerla pública. {@code null} si aún no existe. */
    @Column(name = "ruta_miniatura", length = 500)
    private String rutaMiniatura;

    /** Instante del último cambio de visibilidad (D66): ordena los eventos {@code evidencia.*}. */
    @Column(name = "publica_cambio_en")
    private Instant publicaCambioEn;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant creadoEn;

    protected EvidenciaHallazgo() {
    }

    @SuppressWarnings("java:S107")
    public EvidenciaHallazgo(UUID hallazgoId, String nombre, TipoEvidencia tipo, String contentType,
                             long tamanoBytes, String ruta, String sha256, String subidoPor) {
        this(UUID.randomUUID(), hallazgoId, nombre, tipo, contentType, tamanoBytes, ruta, sha256, subidoPor);
    }

    /** Con el id ya elegido: el archivo se guarda en disco con ese id antes de insertar la fila. */
    @SuppressWarnings("java:S107")
    public EvidenciaHallazgo(UUID id, UUID hallazgoId, String nombre, TipoEvidencia tipo, String contentType,
                             long tamanoBytes, String ruta, String sha256, String subidoPor) {
        this.id = id;
        this.hallazgoId = hallazgoId;
        this.nombre = nombre;
        this.tipo = tipo;
        this.contentType = contentType;
        this.tamanoBytes = tamanoBytes;
        this.ruta = ruta;
        this.sha256 = sha256;
        this.subidoPor = subidoPor;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHallazgoId() {
        return hallazgoId;
    }

    public String getNombre() {
        return nombre;
    }

    public TipoEvidencia getTipo() {
        return tipo;
    }

    public String getContentType() {
        return contentType;
    }

    public long getTamanoBytes() {
        return tamanoBytes;
    }

    public String getRuta() {
        return ruta;
    }

    public String getSha256() {
        return sha256;
    }

    public String getSubidoPor() {
        return subidoPor;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public boolean isPublica() {
        return publica;
    }

    public String getRutaMiniatura() {
        return rutaMiniatura;
    }

    public Instant getPublicaCambioEn() {
        return publicaCambioEn;
    }

    /** Solo una fotografía puede ser pública: un audio no se muestra en el portal. */
    public void marcarPublica(boolean valor, Instant cambioEn) {
        if (valor && tipo != TipoEvidencia.FOTOGRAFIA) {
            throw new IllegalStateException("Solo una fotografía puede ser pública");
        }
        this.publica = valor;
        this.publicaCambioEn = cambioEn;
    }

    /** Fija la ruta de la miniatura ya guardada en disco (D66). */
    public void registrarMiniatura(String ruta) {
        this.rutaMiniatura = ruta;
    }

    /** Fija el instante de cambio de una foto que ya era pública antes de D66 (migración). */
    public void fijarCambioEn(Instant cambioEn) {
        this.publicaCambioEn = cambioEn;
    }

    /** Tipo de evidencia admitida por HU-01. */
    public enum TipoEvidencia {
        FOTOGRAFIA,
        AUDIO
    }
}
