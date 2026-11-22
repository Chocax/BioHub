package co.edu.unbosque.biohub.consultapublica.eventos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.unbosque.biohub.consultapublica.soporte.Fotos;

/** D66 (04 §7.6): la miniatura se valida antes de guardarse. */
class ValidadorFotoJpegTest {

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    @DisplayName("un JPEG de ImageIO con su sha256 es válido")
    void jpegValido() {
        byte[] jpeg = Fotos.jpeg(60, 40);
        assertThat(ValidadorFotoJpeg.validar(b64(jpeg), Fotos.sha256(jpeg))).isEqualTo(jpeg);
        assertThat(ValidadorFotoJpeg.validar(b64(jpeg), Fotos.sha256(jpeg).toUpperCase())).isEqualTo(jpeg);
    }

    @Test
    @DisplayName("sha256 que no coincide")
    void sha256Malo() {
        byte[] jpeg = Fotos.jpeg(30, 30);
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar(b64(jpeg), "0".repeat(64)))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("sha256");
    }

    @Test
    @DisplayName("Exif (APP1) e IPTC (APP13) se rechazan")
    void metadatos() {
        byte[] jpeg = Fotos.jpeg(30, 30);
        byte[] exif = Fotos.conExif(jpeg);
        byte[] iptc = Fotos.conSegmento(jpeg, 0xED, new byte[] {1, 2, 3});
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar(b64(exif), Fotos.sha256(exif)))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("APP1");
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar(b64(iptc), Fotos.sha256(iptc)))
                .isInstanceOf(EventoInvalidoException.class);
    }

    @Test
    @DisplayName("otros segmentos APPn (ICC, comentarios) no se confunden con metadatos prohibidos")
    void otrosSegmentosSeAceptan() {
        byte[] jpeg = Fotos.conSegmento(Fotos.jpeg(30, 30), 0xE2, "ICC_PROFILE\0".getBytes());
        assertThat(ValidadorFotoJpeg.validar(b64(jpeg), Fotos.sha256(jpeg))).isEqualTo(jpeg);
    }

    @Test
    @DisplayName("un 0xE1 dentro de los datos comprimidos no es un segmento APP1")
    void bytesDeDatosNoSonMarcadores() {
        // Cualquier imagen real tiene miles de bytes comprimidos: el recorrido salta los datos del escaneo
        byte[] jpeg = Fotos.jpeg(600, 400);
        assertThat(ValidadorFotoJpeg.validar(b64(jpeg), Fotos.sha256(jpeg))).hasSize(jpeg.length);
    }

    @Test
    @DisplayName("no empieza por FF D8, no es Base64, o termina con bytes sobrantes")
    void formatoInvalido() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar(b64(png), Fotos.sha256(png)))
                .isInstanceOf(EventoInvalidoException.class);
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar("esto no es base64 !!!", "0".repeat(64)))
                .isInstanceOf(EventoInvalidoException.class);
        byte[] conCola = Fotos.conBytesAlFinal(Fotos.jpeg(30, 30), new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar(b64(conCola), Fotos.sha256(conCola)))
                .isInstanceOf(EventoInvalidoException.class);
        byte[] truncado = java.util.Arrays.copyOf(Fotos.jpeg(30, 30), 40);
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar(b64(truncado), Fotos.sha256(truncado)))
                .isInstanceOf(EventoInvalidoException.class);
    }

    @Test
    @DisplayName("pasa de 600 KB: se rechaza, incluso antes de decodificar si el Base64 ya es demasiado largo")
    void tamano() {
        byte[] grande = new byte[ValidadorFotoJpeg.MAXIMO_BYTES + 1];
        grande[0] = (byte) 0xFF;
        grande[1] = (byte) 0xD8;
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar(b64(grande), Fotos.sha256(grande)))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("tamaño");
        assertThatThrownBy(() -> ValidadorFotoJpeg.validar("A".repeat(ValidadorFotoJpeg.MAXIMO_BASE64 + 4), "0".repeat(64)))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("tamaño");
    }
}
