package co.edu.unbosque.biohub.consultapublica.soporte;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

/** JPEG reales (los escribe ImageIO, como el Servicio Científico) y eventos {@code evidencia.*} simulados (D66). */
public final class Fotos {

    private Fotos() {
    }

    /** Un JPEG válido de {@code ancho x alto}, sin metadatos: solo el APP0 de JFIF que escribe ImageIO. */
    public static byte[] jpeg(int ancho, int alto) {
        BufferedImage imagen = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < ancho; x++) {
            for (int y = 0; y < alto; y++) {
                imagen.setRGB(x, y, ((x * 7) << 16) | ((y * 5) << 8) | ((x + y) & 0xFF));
            }
        }
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            ImageIO.write(imagen, "jpeg", salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inserta un segmento {@code APPn} justo después de SOI. */
    public static byte[] conSegmento(byte[] jpeg, int marcador, byte[] contenido) {
        int largo = contenido.length + 2;
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        salida.write(jpeg, 0, 2);
        salida.write(0xFF);
        salida.write(marcador);
        salida.write(largo >> 8);
        salida.write(largo & 0xFF);
        salida.write(contenido, 0, contenido.length);
        salida.write(jpeg, 2, jpeg.length - 2);
        return salida.toByteArray();
    }

    /** JPEG con un segmento APP1 de Exif. */
    public static byte[] conExif(byte[] jpeg) {
        return conSegmento(jpeg, 0xE1, "Exif\0\0datos".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    public static byte[] conBytesAlFinal(byte[] jpeg, byte[] extra) {
        byte[] r = java.util.Arrays.copyOf(jpeg, jpeg.length + extra.length);
        System.arraycopy(extra, 0, r, jpeg.length, extra.length);
        return r;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code datos} de {@code evidencia.publicada} (contrato 04, §7.6). */
    public static Map<String, Object> datosPublicada(UUID evidenciaId, UUID hallazgoId, String cambioEn, byte[] jpeg,
                                                     int ancho, int alto) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("evidenciaId", evidenciaId.toString());
        d.put("hallazgoId", hallazgoId.toString());
        d.put("cambioEn", cambioEn);
        d.put("sha256", sha256(jpeg));
        d.put("ancho", ancho);
        d.put("alto", alto);
        d.put("jpegBase64", Base64.getEncoder().encodeToString(jpeg));
        return d;
    }

    /** {@code datos} de {@code evidencia.retirada}. */
    public static Map<String, Object> datosRetirada(UUID evidenciaId, UUID hallazgoId, String cambioEn) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("evidenciaId", evidenciaId.toString());
        d.put("hallazgoId", hallazgoId.toString());
        d.put("cambioEn", cambioEn);
        return d;
    }
}
