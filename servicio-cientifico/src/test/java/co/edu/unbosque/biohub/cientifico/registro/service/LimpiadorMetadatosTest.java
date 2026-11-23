package co.edu.unbosque.biohub.cientifico.registro.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RNF9: las fotos se guardan sin los metadatos que traen la ubicación GPS. */
public class LimpiadorMetadatosTest {

    /** Texto que simula el bloque GPS de un EXIF real: si aparece en la salida, la ubicación se filtró. */
    static final String GPS = "GPSLatitude=5.53214;GPSLongitude=-73.36712";

    static BufferedImage imagen() {
        BufferedImage img = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 8; y++) {
                img.setRGB(x, y, (x * 15) << 16 | (y * 30) << 8 | 0x40);
            }
        }
        return img;
    }

    static byte[] codificar(String formato) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(imagen(), formato, salida);
        return salida.toByteArray();
    }

    /** JPEG con un APP1 Exif (con "GPS") y un comentario insertados justo después del SOI. */
    public static byte[] jpegConGps() throws IOException {
        byte[] jpeg = codificar("jpg");
        byte[] exif = ("Exif\0\0" + GPS).getBytes(StandardCharsets.ISO_8859_1);
        byte[] comentario = ("Tomada en " + GPS).getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(jpeg, 0, 2);
        segmento(s, 0xE1, exif);
        segmento(s, 0xFE, comentario);
        s.write(jpeg, 2, jpeg.length - 2);
        return s.toByteArray();
    }

    private static void segmento(ByteArrayOutputStream s, int marcador, byte[] datos) {
        s.write(0xFF);
        s.write(marcador);
        int largo = datos.length + 2;
        s.write(largo >> 8);
        s.write(largo & 0xFF);
        s.writeBytes(datos);
    }

    /** PNG con bloques eXIf y tEXt (con "GPS") antes de IEND, con su CRC válido. */
    static byte[] pngConGps() throws IOException {
        byte[] png = codificar("png");
        int iend = png.length - 12;
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(png, 0, iend);
        bloque(s, "eXIf", GPS.getBytes(StandardCharsets.ISO_8859_1));
        bloque(s, "tEXt", ("Location\0" + GPS).getBytes(StandardCharsets.ISO_8859_1));
        s.write(png, iend, 12);
        return s.toByteArray();
    }

    private static void bloque(ByteArrayOutputStream s, String tipo, byte[] datos) {
        byte[] t = tipo.getBytes(StandardCharsets.US_ASCII);
        s.writeBytes(ByteBuffer.allocate(4).putInt(datos.length).array());
        s.writeBytes(t);
        s.writeBytes(datos);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(datos);
        s.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static boolean contiene(byte[] datos, String texto) {
        return new String(datos, StandardCharsets.ISO_8859_1).contains(texto);
    }

    @Test
    void elJpegPierdeElExifYElComentarioPeroLaImagenQuedaIgual() throws IOException {
        byte[] original = jpegConGps();
        assertThat(contiene(original, "GPSLatitude")).isTrue();

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.JPEG, original);

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        assertThat(contiene(limpio, "Exif")).isFalse();
        BufferedImage antes = ImageIO.read(new ByteArrayInputStream(original));
        BufferedImage despues = ImageIO.read(new ByteArrayInputStream(limpio));
        assertThat(despues.getWidth()).isEqualTo(antes.getWidth());
        assertThat(despues.getRGB(3, 3)).isEqualTo(antes.getRGB(3, 3));
    }

    @Test
    void loQueVienePegadoDespuesDelFinDeImagenNoSeGuarda() throws IOException {
        // Así son las "fotos en movimiento" (video MP4 detrás de la foto) y las imágenes secundarias de MPF:
        // cada una con su propia ubicación.
        byte[] jpeg = jpegConGps();
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.writeBytes(jpeg);
        s.writeBytes(("\0\0\0\u0018ftypmp42" + "©xyz+05.5321-073.3671/" + GPS).getBytes(StandardCharsets.ISO_8859_1));
        s.writeBytes(jpegConGps()); // segunda imagen completa, con su EXIF

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.JPEG, s.toByteArray());

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        assertThat(contiene(limpio, "ftyp")).isFalse();
        assertThat(limpio[limpio.length - 2] & 0xFF).isEqualTo(0xFF);
        assertThat(limpio[limpio.length - 1] & 0xFF).isEqualTo(0xD9);
        assertThat(ImageIO.read(new ByteArrayInputStream(limpio)).getRGB(3, 3))
                .isEqualTo(ImageIO.read(new ByteArrayInputStream(jpeg)).getRGB(3, 3));
    }

    @Test
    void losMetadatosDespuesDelEscaneoTambienSeQuitan() throws IOException {
        byte[] jpeg = codificar("jpg");
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(jpeg, 0, jpeg.length - 2); // todo menos el fin de imagen
        segmento(s, 0xE1, ("Exif\0\0" + GPS).getBytes(StandardCharsets.ISO_8859_1));
        segmento(s, 0xE0, ("JFXX\0" + GPS).getBytes(StandardCharsets.ISO_8859_1));
        s.write(0xFF);
        s.write(0xD9);

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.JPEG, s.toByteArray());

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        assertThat(limpio).isEqualTo(jpeg);
    }

    @Test
    void unJpegProgresivoSeRecorreCompletoYQuedaIgual() throws IOException {
        ByteArrayOutputStream progresivo = new ByteArrayOutputStream();
        javax.imageio.ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpg").next();
        javax.imageio.ImageWriteParam parametros = escritor.getDefaultWriteParam();
        parametros.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
        try (var salida = ImageIO.createImageOutputStream(progresivo)) {
            escritor.setOutput(salida);
            escritor.write(null, new javax.imageio.IIOImage(imagen(), null, null), parametros);
        }
        escritor.dispose();
        byte[] jpeg = progresivo.toByteArray();
        ByteArrayOutputStream conGps = new ByteArrayOutputStream();
        conGps.write(jpeg, 0, 2);
        segmento(conGps, 0xE1, ("Exif\0\0" + GPS).getBytes(StandardCharsets.ISO_8859_1));
        conGps.write(jpeg, 2, jpeg.length - 2);

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.JPEG, conGps.toByteArray());

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        assertThat(limpio).isEqualTo(jpeg);
    }

    @Test
    void unJpegSinFinDeImagenSeCierraYSeGuarda() throws IOException {
        byte[] jpeg = codificar("jpg");
        byte[] sinFin = Arrays.copyOf(jpeg, jpeg.length - 2);

        assertThat(LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.JPEG, sinFin)).isEqualTo(jpeg);
    }

    @Test
    void elPngPierdeEXIfYTextoPeroLaImagenQuedaIgual() throws IOException {
        byte[] original = pngConGps();
        assertThat(contiene(original, "GPSLatitude")).isTrue();

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.PNG, original);

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        BufferedImage despues = ImageIO.read(new ByteArrayInputStream(limpio));
        assertThat(despues.getRGB(5, 2)).isEqualTo(imagen().getRGB(5, 2));
    }

    @Test
    void unaImagenSinMetadatosNoCambia() throws IOException {
        byte[] png = codificar("png");

        assertThat(LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.PNG, png)).isEqualTo(png);
    }

    @Test
    void unaImagenCortadaSeRechaza() throws IOException {
        byte[] jpeg = jpegConGps();
        byte[] cortado = Arrays.copyOf(jpeg, 20);

        assertThatThrownBy(() -> LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.JPEG, cortado))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.PNG, Arrays.copyOf(codificar("png"), 30)))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    @ParameterizedTest
    @CsvSource({"52494646000000005741564566, WAV", "4944330300, MP3", "FFFB9064, MP3", "4F67675300, OGG",
            "664C614300, FLAC", "FFD8FFE0, JPEG", "89504E470D0A1A0A00, PNG"})
    void reconoceElFormatoPorSuFirma(String hex, TipoArchivoEvidencia esperado) {
        assertThat(TipoArchivoEvidencia.detectar(java.util.HexFormat.of().parseHex(hex))).contains(esperado);
    }

    @Test
    void rechazaFormatosNoAdmitidos() {
        // WEBP (RIFF....WEBP), HTML y un ejecutable
        assertThat(TipoArchivoEvidencia.detectar("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(TipoArchivoEvidencia.detectar("<html><script>".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(TipoArchivoEvidencia.detectar(new byte[] {'M', 'Z', 0, 0})).isEmpty();
    }

    @Test
    void elNombreVisibleNoTraeCarpetasNiExtensionFalsa() {
        assertThat(EvidenciaService.nombreVisible("..\\..\\etc/passwd.png", TipoArchivoEvidencia.JPEG))
                .isEqualTo("passwd.jpeg");
        assertThat(EvidenciaService.nombreVisible("canto<rana>.mp3", TipoArchivoEvidencia.MP3))
                .isEqualTo("cantorana.mp3");
        assertThat(EvidenciaService.nombreVisible(null, TipoArchivoEvidencia.WAV)).isEqualTo("audio.wav");
    }
}
