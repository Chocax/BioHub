package co.edu.unbosque.biohub.consultapublica.infrastructure;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import co.edu.unbosque.biohub.consultapublica.domain.FotoPublica;
import co.edu.unbosque.biohub.consultapublica.domain.FotoServida;

/**
 * Tabla {@code foto_publica} de la base de lectura (D66). Todas las sentencias son parametrizadas. La comparación
 * por {@code cambio_en} va dentro del propio {@code UPDATE}, así que un evento repetido o desordenado no cambia nada
 * y no hace falta bloquear la fila. Los instantes se guardan en UTC y con microsegundos (lo que admite
 * {@code DATETIME(6)}); por eso se truncan antes de comparar.
 */
@Repository
public class FotosPublicasJdbc {

    private final JdbcClient jdbc;

    public FotosPublicasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static LocalDateTime enUtc(Instant instante) {
        return LocalDateTime.ofInstant(instante.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** UPDATE condicionado a que el cambio guardado sea anterior. Devuelve las filas cambiadas (0 o 1). */
    public int actualizarPublicada(FotoPublica f) {
        return jdbc.sql("""
                        UPDATE foto_publica SET hallazgo_id = ?, bytes = ?, sha256 = ?, ancho = ?, alto = ?,
                               publica = TRUE, cambio_en = ?
                        WHERE evidencia_id = ? AND cambio_en < ?""")
                .param(UuidBinario.aBytes(f.hallazgoId())).param(f.bytes()).param(f.sha256())
                .param(f.ancho()).param(f.alto()).param(enUtc(f.cambioEn()))
                .param(UuidBinario.aBytes(f.evidenciaId())).param(enUtc(f.cambioEn()))
                .update();
    }

    /** INSERT; lanza {@code DuplicateKeyException} si la foto ya existe. */
    public void insertarPublicada(FotoPublica f) {
        jdbc.sql("""
                        INSERT INTO foto_publica (evidencia_id, hallazgo_id, bytes, sha256, ancho, alto, publica, cambio_en)
                        VALUES (?,?,?,?,?,?,TRUE,?)""")
                .param(UuidBinario.aBytes(f.evidenciaId())).param(UuidBinario.aBytes(f.hallazgoId()))
                .param(f.bytes()).param(f.sha256()).param(f.ancho()).param(f.alto()).param(enUtc(f.cambioEn()))
                .update();
    }

    /** La foto deja de ser pública y se borran sus bytes. Devuelve las filas cambiadas (0 o 1). */
    public int actualizarRetirada(UUID evidenciaId, UUID hallazgoId, Instant cambioEn) {
        return jdbc.sql("""
                        UPDATE foto_publica SET hallazgo_id = ?, bytes = NULL, sha256 = NULL, ancho = NULL, alto = NULL,
                               publica = FALSE, cambio_en = ?
                        WHERE evidencia_id = ? AND cambio_en < ?""")
                .param(UuidBinario.aBytes(hallazgoId)).param(enUtc(cambioEn))
                .param(UuidBinario.aBytes(evidenciaId)).param(enUtc(cambioEn))
                .update();
    }

    /**
     * INSERT de una retirada que llega antes que su publicación: deja la marca {@code publica = FALSE} para que la
     * publicación anterior, si llega tarde, se ignore. Lanza {@code DuplicateKeyException} si la foto ya existe.
     */
    public void insertarRetirada(UUID evidenciaId, UUID hallazgoId, Instant cambioEn) {
        jdbc.sql("INSERT INTO foto_publica (evidencia_id, hallazgo_id, publica, cambio_en) VALUES (?,?,FALSE,?)")
                .param(UuidBinario.aBytes(evidenciaId)).param(UuidBinario.aBytes(hallazgoId)).param(enUtc(cambioEn))
                .update();
    }

    /** El sha256 (ETag) de una foto pública, sin leer los bytes; vacío si no existe o ya no es pública. */
    public Optional<String> shaPublica(UUID evidenciaId) {
        return jdbc.sql("SELECT sha256 FROM foto_publica WHERE evidencia_id = ? AND publica = TRUE")
                .param(UuidBinario.aBytes(evidenciaId))
                .query(String.class)
                .optional();
    }

    public Optional<FotoServida> publicaPorId(UUID evidenciaId) {
        return jdbc.sql("SELECT evidencia_id, sha256, bytes FROM foto_publica WHERE evidencia_id = ? AND publica = TRUE")
                .param(UuidBinario.aBytes(evidenciaId))
                .query((rs, n) -> new FotoServida(UuidBinario.aUuid(rs.getBytes("evidencia_id")),
                        rs.getString("sha256"), rs.getBytes("bytes")))
                .optional();
    }

    /** La foto pública más reciente de una especie (compatibilidad con {@code ?nombre=}); sin distinguir mayúsculas ni tildes. */
    public Optional<FotoServida> masRecientePorEspecie(String nombreCientifico) {
        return jdbc.sql("""
                        SELECT fp.evidencia_id, fp.sha256, fp.bytes
                        FROM foto_publica fp JOIN ficha_publica f ON f.hallazgo_id = fp.hallazgo_id
                        WHERE fp.publica = TRUE AND f.nombre_cientifico = ?
                        ORDER BY fp.cambio_en DESC, fp.evidencia_id LIMIT 1""")
                .param(nombreCientifico)
                .query((rs, n) -> new FotoServida(UuidBinario.aUuid(rs.getBytes("evidencia_id")),
                        rs.getString("sha256"), rs.getBytes("bytes")))
                .optional();
    }
}
