package co.edu.unbosque.biohub.cientifico.registro.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.unbosque.biohub.cientifico.registro.service.FotoPublica.ImagenNoAdmitidaException;
import co.edu.unbosque.biohub.cientifico.registro.service.FotoPublica.Miniatura;

/** D66: la miniatura pública (tamaño, sin metadatos, límite de megapíxeles). */
class FotoPublicaTest {

    private static byte[] imagen(int ancho, int alto, String formato, boolean ruido) throws Exception {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        if (ruido) {
            Random azar = new Random(7);
            for (int x = 0; x < ancho; x++) {
                for (int y = 0; y < alto; y++) {
                    img.setRGB(x, y, azar.nextInt(0x1000000));
                }
            }
        } else {
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(60, 120, 40));
            g.fillRect(0, 0, ancho, alto);
            g.dispose();
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(img, formato, salida);
        return salida.toByteArray();
    }

    /** Marcadores JPEG presentes antes del inicio del escaneo (SOS). */
    static List<Integer> marcadores(byte[] jpeg) {
        List<Integer> lista = new ArrayList<>();
        int i = 2;
        while (i + 4 <= jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                break;
            }
            int marcador = jpeg[i + 1] & 0xFF;
            lista.add(marcador);
            if (marcador == 0xDA) {
                break;
            }
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
        return lista;
    }

    private static byte[] pngQueDeclara(int ancho, int alto) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(ancho).putInt(alto).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(s, "IHDR", ihdr.array());
        chunk(s, "IEND", new byte[0]);
        return s.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream s, String tipo, byte[] datos) {
        byte[] t = tipo.getBytes(StandardCharsets.US_ASCII);
        s.writeBytes(ByteBuffer.allocate(4).putInt(datos.length).array());
        s.writeBytes(t);
        s.writeBytes(datos);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(datos);
        s.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    @DisplayName("una foto grande se reduce a 1200 px de lado, en JPEG y a lo sumo 600 KB")
    void reduceAMilDoscientos() throws Exception {
        Miniatura m = FotoPublica.generar(imagen(3000, 2000, "jpg", false));

        assertThat(m.ancho()).isEqualTo(1200);
        assertThat(m.alto()).isEqualTo(800);
        assertThat(m.jpeg().length).isLessThanOrEqualTo(FotoPublica.TAMANO_MAXIMO);
        assertThat(m.jpeg()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(m.jpeg()[1] & 0xFF).isEqualTo(0xD8);
        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(m.jpeg()));
        assertThat(leida.getWidth()).isEqualTo(1200);
        assertThat(leida.getHeight()).isEqualTo(800);
    }

    @Test
    @DisplayName("una foto pequeña no se amplía")
    void noAmplia() throws Exception {
        Miniatura m = FotoPublica.generar(imagen(300, 200, "png", false));

        assertThat(m.ancho()).isEqualTo(300);
        assertThat(m.alto()).isEqualTo(200);
    }

    @Test
    @DisplayName("una imagen difícil de comprimir (ruido) baja calidad o lado hasta caber en 600 KB")
    void elRuidoCabeEnElTope() throws Exception {
        Miniatura m = FotoPublica.generar(imagen(2400, 2400, "png", true));

        assertThat(m.jpeg().length).isLessThanOrEqualTo(FotoPublica.TAMANO_MAXIMO);
        assertThat(Math.max(m.ancho(), m.alto())).isLessThanOrEqualTo(FotoPublica.LADO_MAXIMO);
        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(m.jpeg()));
        assertThat(leida.getWidth()).isEqualTo(m.ancho());
    }

    @Test
    @DisplayName("la miniatura no trae EXIF, XMP, comentarios ni coordenadas del original")
    void sinMetadatos() throws Exception {
        Miniatura m = FotoPublica.generar(LimpiadorMetadatosTest.jpegConGps());

        List<Integer> marcadores = marcadores(m.jpeg());
        assertThat(marcadores).doesNotContain(0xE1, 0xED, 0xFE); // APP1 (Exif/XMP), APP13 (Photoshop), COM
        assertThat(new String(m.jpeg(), StandardCharsets.ISO_8859_1))
                .doesNotContain("GPSLatitude").doesNotContain("Exif");
    }

    @Test
    @DisplayName("una imagen de más de 40 MP se rechaza leyendo solo el encabezado, sin decodificarla")
    void rechazaMasDeCuarentaMegapixeles() {
        // 10000 x 5000 = 50 MP declarados en un PNG sin datos: decodificarlo fallaría de otra forma o agotaría
        // el heap; el rechazo ocurre antes, por las dimensiones.
        assertThatThrownBy(() -> FotoPublica.generar(pngQueDeclara(10_000, 5_000)))
                .isInstanceOf(ImagenNoAdmitidaException.class)
                .hasMessageContaining("40 megap");
    }

    @Test
    @DisplayName("justo en el límite de 40 MP se acepta el encabezado (falla después por no tener datos, no por tamaño)")
    void enElLimiteNoSeRechazaPorTamano() {
        assertThatThrownBy(() -> FotoPublica.generar(pngQueDeclara(8_000, 5_000)))
                .isInstanceOf(ImagenNoAdmitidaException.class)
                .hasMessageNotContaining("40 megap");
    }

    @Test
    @DisplayName("bytes que no son una imagen se rechazan")
    void rechazaBasura() {
        assertThatThrownBy(() -> FotoPublica.generar("no soy una foto".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImagenNoAdmitidaException.class);
        assertThatThrownBy(() -> FotoPublica.generar(new byte[0])).isInstanceOf(ImagenNoAdmitidaException.class);
    }

    @Test
    @DisplayName("dimensiones lee ancho y alto de un JPEG; con basura no devuelve nada")
    void leeDimensiones() throws Exception {
        assertThat(FotoPublica.dimensiones(imagen(640, 480, "jpg", false)).orElseThrow()).containsExactly(640, 480);
        assertThat(FotoPublica.dimensiones(new byte[] {1, 2, 3})).isEmpty();
    }
}
