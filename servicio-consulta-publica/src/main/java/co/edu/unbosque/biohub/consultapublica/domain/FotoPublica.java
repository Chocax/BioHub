package co.edu.unbosque.biohub.consultapublica.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Miniatura pública ya validada de una evidencia (D66, contrato 04 §7.6). {@code evidenciaId} es el {@code fotoId}
 * que ve el portal; la especie no se guarda: sale de la ficha por {@code hallazgoId}.
 *
 * @param bytes    JPEG de a lo sumo 600 KB, de a lo sumo 1200 px y sin metadatos (se comprobó antes de crear el objeto)
 * @param cambioEn instante del cambio de visibilidad; ordena los eventos de una misma foto
 */
public record FotoPublica(UUID evidenciaId, UUID hallazgoId, String sha256, int ancho, int alto, byte[] bytes,
                          Instant cambioEn) {
}
