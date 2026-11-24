package co.edu.unbosque.biohub.consultapublica.eventos.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Valida la miniatura de un {@code evidencia.publicada} (contrato 04, §7.6) antes de guardarla. Una foto pública sale
 * al visitante sin ninguna otra revisión, así que aquí se exige que de verdad sea lo que dice ser:
 * <ul>
 *   <li>Base64 estándar que decodifica y de a lo sumo 600 KB (se mide antes de decodificar, para no reservar memoria
 *       por un campo desmesurado);</li>
 *   <li>empieza por {@code FF D8} y su sha256 coincide con el declarado;</li>
 *   <li>los segmentos del JPEG están bien formados, sin {@code APP1} (Exif/XMP) ni {@code APP13} (IPTC/Photoshop), y
 *       termina en {@code EOI} sin bytes después (ahí se esconden los "Motion Photo" y las segundas imágenes).</li>
 * </ul>
 * Los mensajes de error nunca incluyen contenido del evento.
 */
final class ValidadorFotoJpeg {

    /** Tamaño máximo del JPEG decodificado. */
    static final int MAXIMO_BYTES = 600 * 1024;
    /** Largo máximo del texto Base64 que corresponde a {@link #MAXIMO_BYTES} (con relleno). */
    static final int MAXIMO_BASE64 = ((MAXIMO_BYTES + 2) / 3) * 4;

    private ValidadorFotoJpeg() {
    }

    /** Decodifica y valida; devuelve los bytes del JPEG o lanza {@link EventoInvalidoException} (DLQ). */
    static byte[] validar(String jpegBase64, String sha256Declarado) {
        if (jpegBase64 == null || jpegBase64.length() < 8 || jpegBase64.length() > MAXIMO_BASE64) {
            throw new EventoInvalidoException("Foto con tamaño no permitido");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(jpegBase64);
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("Foto con Base64 inválido");
        }
        if (bytes.length > MAXIMO_BYTES) {
            throw new EventoInvalidoException("Foto con tamaño no permitido");
        }
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            throw new EventoInvalidoException("La foto no es un JPEG");
        }
        if (!sha256(bytes).equalsIgnoreCase(sha256Declarado)) {
            throw new EventoInvalidoException("El sha256 de la foto no coincide");
        }
        exigirSegmentosSinMetadatos(bytes);
        return bytes;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Recorre los marcadores del JPEG, incluidos los que van tras los datos de cada escaneo. */
    private static void exigirSegmentosSinMetadatos(byte[] b) {
        int n = b.length;
        int i = 2; // tras SOI
        while (i < n) {
            if ((b[i] & 0xFF) != 0xFF) {
                throw new EventoInvalidoException("JPEG mal formado");
            }
            while (i < n && (b[i] & 0xFF) == 0xFF) { // bytes de relleno antes del marcador
                i++;
            }
            if (i >= n) {
                throw new EventoInvalidoException("JPEG mal formado");
            }
            int marcador = b[i++] & 0xFF;
            if (marcador == 0x00) {
                throw new EventoInvalidoException("JPEG mal formado");
            }
            if (marcador == 0xD9) { // EOI
                if (i != n) {
                    throw new EventoInvalidoException("JPEG con datos después del final");
                }
                return;
            }
            if (marcador == 0xD8 || marcador == 0x01 || (marcador >= 0xD0 && marcador <= 0xD7)) {
                continue; // sin longitud
            }
            if (i + 2 > n) {
                throw new EventoInvalidoException("JPEG mal formado");
            }
            int largo = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            if (largo < 2 || i + largo > n) {
                throw new EventoInvalidoException("JPEG mal formado");
            }
            if (marcador == 0xE1 || marcador == 0xED) {
                throw new EventoInvalidoException("La foto trae metadatos (APP1/APP13)");
            }
            i += largo;
            if (marcador == 0xDA) { // SOS: datos del escaneo hasta el siguiente marcador de verdad
                i = saltarDatosDelEscaneo(b, i);
            }
        }
        throw new EventoInvalidoException("JPEG sin marcador final");
    }

    private static int saltarDatosDelEscaneo(byte[] b, int desde) {
        int i = desde;
        while (i < b.length) {
            if ((b[i] & 0xFF) == 0xFF) {
                int siguiente = i + 1 < b.length ? b[i + 1] & 0xFF : -1;
                if (siguiente == 0x00 || (siguiente >= 0xD0 && siguiente <= 0xD7)) {
                    i += 2; // byte de relleno de un 0xFF o marcador de reinicio
                    continue;
                }
                if (siguiente == 0xFF) {
                    i++;
                    continue;
                }
                return i; // marcador real
            }
            i++;
        }
        return i;
    }
}
