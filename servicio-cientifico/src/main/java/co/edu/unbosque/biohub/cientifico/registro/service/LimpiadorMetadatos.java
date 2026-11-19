package co.edu.unbosque.biohub.cientifico.registro.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Quita los metadatos de las fotografías antes de guardarlas (RNF9, ASR6).
 *
 * <p><b>Por qué:</b> una foto de celular lleva en su EXIF las coordenadas GPS exactas de donde se tomó. Si se
 * guardara tal cual, la ubicación de una especie en peligro quedaría en disco en texto plano, sin cifrar, y saldría
 * con cada descarga. La imagen se reescribe sin esos bloques; los píxeles no cambian.
 *
 * <ul>
 *   <li><b>JPEG:</b> se eliminan los segmentos APP1 a APP15 (EXIF, XMP, IPTC...) y los comentarios, salvo el perfil
 *       de color ICC (APP2 {@code ICC_PROFILE}) y el bloque Adobe (APP14), que hacen falta para ver bien los colores.
 *       El resto de la imagen, desde el inicio del escaneo, se copia igual.</li>
 *   <li><b>PNG:</b> se eliminan los bloques {@code eXIf}, {@code tEXt}, {@code zTXt}, {@code iTXt} y {@code tIME}.</li>
 * </ul>
 * Un archivo mal formado se rechaza: no se guarda algo que no se pudo revisar.
 */
final class LimpiadorMetadatos {

    private static final Set<String> BLOQUES_PNG_QUITADOS = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

    private LimpiadorMetadatos() {
    }

    static byte[] limpiar(TipoArchivoEvidencia tipo, byte[] datos) {
        return switch (tipo) {
            case JPEG -> jpeg(datos);
            case PNG -> png(datos);
            default -> datos;
        };
    }

    private static byte[] jpeg(byte[] d) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length);
        salida.write(0xFF);
        salida.write(0xD8);
        int i = 2;
        while (i < d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                throw invalido();
            }
            while (i < d.length && (d[i] & 0xFF) == 0xFF) {
                i++; // bytes de relleno antes del marcador
            }
            if (i >= d.length) {
                throw invalido();
            }
            int marcador = d[i] & 0xFF;
            i++;
            if (marcador == 0xD9) { // fin de imagen
                salida.write(0xFF);
                salida.write(0xD9);
                return salida.toByteArray();
            }
            if ((marcador >= 0xD0 && marcador <= 0xD7) || marcador == 0x01) {
                salida.write(0xFF);
                salida.write(marcador);
                continue;
            }
            if (i + 2 > d.length) {
                throw invalido();
            }
            int largo = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            if (largo < 2 || i + largo > d.length) {
                throw invalido();
            }
            if (marcador == 0xDA) { // inicio del escaneo: de aquí en adelante son los datos de la imagen
                salida.write(0xFF);
                salida.write(marcador);
                salida.write(d, i, d.length - i);
                return salida.toByteArray();
            }
            if (!quitarSegmentoJpeg(marcador, d, i + 2, largo - 2)) {
                salida.write(0xFF);
                salida.write(marcador);
                salida.write(d, i, largo);
            }
            i += largo;
        }
        throw invalido();
    }

    private static boolean quitarSegmentoJpeg(int marcador, byte[] d, int inicio, int largo) {
        if (marcador == 0xFE) {
            return true; // comentario
        }
        if (marcador == 0xE2) {
            return !empiezaCon(d, inicio, largo, "ICC_PROFILE");
        }
        if (marcador == 0xEE) {
            return !empiezaCon(d, inicio, largo, "Adobe");
        }
        return marcador >= 0xE1 && marcador <= 0xEF;
    }

    private static boolean empiezaCon(byte[] d, int inicio, int largo, String texto) {
        byte[] t = texto.getBytes(StandardCharsets.US_ASCII);
        if (largo < t.length) {
            return false;
        }
        for (int k = 0; k < t.length; k++) {
            if (d[inicio + k] != t[k]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] png(byte[] d) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length);
        salida.write(d, 0, 8);
        int i = 8;
        while (i + 12 <= d.length) {
            long largo = ((d[i] & 0xFFL) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (largo > d.length - i - 12L) {
                throw invalido();
            }
            String tipo = new String(d, i + 4, 4, StandardCharsets.US_ASCII);
            int total = (int) largo + 12;
            if (!BLOQUES_PNG_QUITADOS.contains(tipo)) {
                salida.write(d, i, total);
            }
            i += total;
            if ("IEND".equals(tipo)) {
                return salida.toByteArray();
            }
        }
        throw invalido();
    }

    private static DatosHallazgoInvalidosException invalido() {
        return new DatosHallazgoInvalidosException("archivo", "La imagen está dañada o incompleta.");
    }
}
