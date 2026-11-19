package co.edu.unbosque.biohub.cientifico.registro.service;

import java.util.Arrays;
import java.util.Optional;

import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo.TipoEvidencia;

/**
 * Formatos admitidos como evidencia (HU-01) y cómo se reconocen por su contenido ("magic bytes").
 *
 * <p>El tipo se decide por los primeros bytes del archivo, nunca por la extensión ni por el
 * {@code Content-Type} que envía el navegador: ambos los controla quien sube el archivo (SEC-03).
 *
 * <p>No se admiten WEBP, HEIC, MP4 ni M4A: pueden llevar la ubicación del teléfono en sus metadatos y aquí no se
 * limpian. JPEG y PNG sí se limpian ({@link LimpiadorMetadatos}).
 */
public enum TipoArchivoEvidencia {

    JPEG(TipoEvidencia.FOTOGRAFIA, "image/jpeg"),
    PNG(TipoEvidencia.FOTOGRAFIA, "image/png"),
    WAV(TipoEvidencia.AUDIO, "audio/wav"),
    MP3(TipoEvidencia.AUDIO, "audio/mpeg"),
    OGG(TipoEvidencia.AUDIO, "audio/ogg"),
    FLAC(TipoEvidencia.AUDIO, "audio/flac");

    private final TipoEvidencia tipo;
    private final String contentType;

    TipoArchivoEvidencia(TipoEvidencia tipo, String contentType) {
        this.tipo = tipo;
        this.contentType = contentType;
    }

    public TipoEvidencia tipo() {
        return tipo;
    }

    public String contentType() {
        return contentType;
    }

    /** Reconoce el formato por su firma. Vacío si no es ninguno de los admitidos. */
    public static Optional<TipoArchivoEvidencia> detectar(byte[] b) {
        if (empieza(b, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (empieza(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (empieza(b, 'R', 'I', 'F', 'F') && b.length >= 12 && b[8] == 'W' && b[9] == 'A' && b[10] == 'V'
                && b[11] == 'E') {
            return Optional.of(WAV);
        }
        if (empieza(b, 'I', 'D', '3') || (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0)) {
            return Optional.of(MP3);
        }
        if (empieza(b, 'O', 'g', 'g', 'S')) {
            return Optional.of(OGG);
        }
        if (empieza(b, 'f', 'L', 'a', 'C')) {
            return Optional.of(FLAC);
        }
        return Optional.empty();
    }

    private static boolean empieza(byte[] datos, int... firma) {
        if (datos.length < firma.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(datos, firma.length), toBytes(firma));
    }

    private static byte[] toBytes(int[] valores) {
        byte[] r = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) {
            r[i] = (byte) valores[i];
        }
        return r;
    }
}
