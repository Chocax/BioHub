package co.edu.unbosque.biohub.cientifico.registro.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Quita los metadatos de las evidencias antes de guardarlas (RNF9, ASR6).
 *
 * <p><b>Por qué:</b> una foto o una grabación de celular puede llevar en sus metadatos las coordenadas GPS exactas de
 * donde se tomó. Si se guardara tal cual, la ubicación de una especie en peligro quedaría en disco en texto plano, sin
 * cifrar, y saldría con cada descarga. El archivo se reescribe sin esos bloques; los píxeles y el sonido no cambian.
 *
 * <ul>
 *   <li><b>JPEG:</b> se recorre la imagen completa, escaneo por escaneo (también las progresivas), y solo se copian
 *       las tablas y los datos de la imagen. Se eliminan los segmentos APP0 a APP15 (EXIF, XMP, IPTC, MPF...) y los
 *       comentarios, salvo JFIF (APP0), el perfil de color ICC (APP2 {@code ICC_PROFILE}) y el bloque Adobe (APP14),
 *       que hacen falta para ver bien los colores. Se corta en el fin de imagen: lo que venga pegado detrás (el video
 *       de una "foto en movimiento", las imágenes secundarias de MPF con su propio EXIF) no se guarda.</li>
 *   <li><b>PNG:</b> se eliminan los bloques {@code eXIf}, {@code tEXt}, {@code zTXt}, {@code iTXt} y {@code tIME},
 *       y todo lo que venga después de {@code IEND}.</li>
 *   <li><b>Audio</b> (WAV, MP3, OGG, FLAC): solo se conserva el sonido, ver {@link LimpiadorAudio}.</li>
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
            case WAV -> LimpiadorAudio.wav(datos);
            case MP3 -> LimpiadorAudio.mp3(datos);
            case OGG -> LimpiadorAudio.ogg(datos);
            case FLAC -> LimpiadorAudio.flac(datos);
        };
    }

    private static byte[] jpeg(byte[] d) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length);
        salida.write(0xFF);
        salida.write(0xD8);
        boolean huboEscaneo = false;
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
            if (marcador == 0xD9) { // fin de imagen: lo que siga en el archivo no se copia
                return finJpeg(salida, huboEscaneo);
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
            if (!quitarSegmentoJpeg(marcador, d, i + 2, largo - 2)) {
                salida.write(0xFF);
                salida.write(marcador);
                salida.write(d, i, largo);
            }
            i += largo;
            if (marcador == 0xDA) { // inicio del escaneo: siguen los datos comprimidos hasta el próximo marcador
                huboEscaneo = true;
                int fin = finDatosEscaneo(d, i);
                salida.write(d, i, fin - i);
                i = fin;
            }
        }
        // Algunas cámaras no escriben el fin de imagen: se acepta si ya hubo al menos un escaneo.
        return finJpeg(salida, huboEscaneo);
    }

    /**
     * Posición del primer marcador después de los datos comprimidos de un escaneo. Dentro de esos datos un 0xFF va
     * seguido de 0x00 (byte escapado) o de un marcador de reinicio (RST0 a RST7), que siguen siendo parte del escaneo.
     */
    private static int finDatosEscaneo(byte[] d, int desde) {
        int i = desde;
        while (i + 1 < d.length) {
            if ((d[i] & 0xFF) == 0xFF) {
                int siguiente = d[i + 1] & 0xFF;
                if (siguiente != 0x00 && !(siguiente >= 0xD0 && siguiente <= 0xD7)) {
                    return i;
                }
                i += 2;
            } else {
                i++;
            }
        }
        return d.length;
    }

    private static byte[] finJpeg(ByteArrayOutputStream salida, boolean huboEscaneo) {
        if (!huboEscaneo) {
            throw invalido();
        }
        salida.write(0xFF);
        salida.write(0xD9);
        return salida.toByteArray();
    }

    private static boolean quitarSegmentoJpeg(int marcador, byte[] d, int inicio, int largo) {
        if (marcador == 0xFE) {
            return true; // comentario
        }
        if (marcador == 0xE0) {
            return !empiezaCon(d, inicio, largo, "JFIF\0"); // JFXX y otros APP0 pueden llevar miniaturas o texto
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

    static DatosHallazgoInvalidosException invalido() {
        return new DatosHallazgoInvalidosException("archivo", "El archivo está dañado o incompleto.");
    }
}
