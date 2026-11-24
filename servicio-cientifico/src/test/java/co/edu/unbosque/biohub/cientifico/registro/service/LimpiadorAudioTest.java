package co.edu.unbosque.biohub.cientifico.registro.service;

import static co.edu.unbosque.biohub.cientifico.registro.service.LimpiadorMetadatosTest.GPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;

/** RNF9: las grabaciones se guardan solo con el sonido, sin etiquetas que puedan traer la ubicación. */
class LimpiadorAudioTest {

    private static final byte[] TEXTO_GPS = GPS.getBytes(StandardCharsets.ISO_8859_1);

    private static boolean contiene(byte[] datos, String texto) {
        return new String(datos, StandardCharsets.ISO_8859_1).contains(texto);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void le32(ByteArrayOutputStream s, long v) {
        for (int k = 0; k < 4; k++) {
            s.write((int) (v >>> (8 * k)) & 0xFF);
        }
    }

    // ------------------------------------------------------------------------------------------------ WAV

    private static void bloqueRiff(ByteArrayOutputStream s, String id, byte[] datos) {
        s.writeBytes(ascii(id));
        le32(s, datos.length);
        s.writeBytes(datos);
        if (datos.length % 2 == 1) {
            s.write(0);
        }
    }

    /** WAV PCM de 8 bits, mono, 8000 Hz, con LIST/INFO e iXML (con "GPS") antes y después del sonido. */
    static byte[] wavConGps(byte[] muestras) {
        ByteArrayOutputStream fmt = new ByteArrayOutputStream();
        fmt.writeBytes(new byte[] {1, 0, 1, 0}); // PCM, 1 canal
        le32(fmt, 8000);
        le32(fmt, 8000);
        fmt.writeBytes(new byte[] {1, 0, 8, 0}); // alineación 1, 8 bits
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.writeBytes(ascii("INFO"));
        bloqueRiff(info, "ICMT", TEXTO_GPS);
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.writeBytes(ascii("WAVE"));
        bloqueRiff(cuerpo, "fmt ", fmt.toByteArray());
        bloqueRiff(cuerpo, "LIST", info.toByteArray());
        bloqueRiff(cuerpo, "data", muestras);
        bloqueRiff(cuerpo, "iXML", ascii("<LOCATION>" + GPS + "</LOCATION>"));
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        wav.writeBytes(ascii("RIFF"));
        le32(wav, cuerpo.size());
        wav.writeBytes(cuerpo.toByteArray());
        return wav.toByteArray();
    }

    @Test
    void elWavConservaSoloFormatoYSonido() throws Exception {
        byte[] muestras = new byte[801]; // impar, para probar la alineación
        for (int i = 0; i < muestras.length; i++) {
            muestras[i] = (byte) (128 + 100 * Math.sin(i / 5.0));
        }
        byte[] original = wavConGps(muestras);
        assertThat(contiene(original, "GPSLatitude")).isTrue();

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.WAV, original);

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        assertThat(contiene(limpio, "LIST")).isFalse();
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(limpio))) {
            assertThat(audio.getFormat().getSampleRate()).isEqualTo(8000f);
            assertThat(audio.readAllBytes()).isEqualTo(muestras);
        }
    }

    @Test
    void unWavSinSonidoSeRechaza() {
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        wav.writeBytes(ascii("RIFF"));
        le32(wav, 4 + 8 + TEXTO_GPS.length);
        wav.writeBytes(ascii("WAVE"));
        bloqueRiff(wav, "LIST", TEXTO_GPS);

        assertThatThrownBy(() -> LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.WAV, wav.toByteArray()))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    // ------------------------------------------------------------------------------------------------ MP3

    /** Trama MPEG1 capa III, 128 kbps, 44,1 kHz, sin relleno: 417 bytes. El cuerpo lleva el número de trama. */
    static byte[] tramaMp3(int n) {
        byte[] t = new byte[417];
        t[0] = (byte) 0xFF;
        t[1] = (byte) 0xFB;
        t[2] = (byte) 0x90;
        t[3] = (byte) 0x64;
        Arrays.fill(t, 4, t.length, (byte) n);
        return t;
    }

    private static byte[] id3v2(String texto) {
        ByteArrayOutputStream marco = new ByteArrayOutputStream();
        marco.writeBytes(ascii("TXXX"));
        byte[] contenido = ascii("\0GPS\0" + texto);
        marco.writeBytes(new byte[] {0, 0, (byte) (contenido.length >> 8), (byte) contenido.length, 0, 0});
        marco.writeBytes(contenido);
        int tam = marco.size();
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.writeBytes(ascii("ID3"));
        tag.writeBytes(new byte[] {3, 0, 0, (byte) ((tam >> 21) & 0x7F), (byte) ((tam >> 14) & 0x7F),
                (byte) ((tam >> 7) & 0x7F), (byte) (tam & 0x7F)});
        tag.writeBytes(marco.toByteArray());
        return tag.toByteArray();
    }

    private static byte[] apeConPie(String texto) {
        byte[] item = ascii(texto);
        ByteArrayOutputStream ape = new ByteArrayOutputStream();
        le32(ape, item.length);
        le32(ape, 0);
        ape.writeBytes(ascii("Location\0"));
        ape.writeBytes(item);
        int tamItems = ape.size();
        ape.writeBytes(ascii("APETAGEX"));
        le32(ape, 2000);
        le32(ape, tamItems + 32);
        le32(ape, 1);
        le32(ape, 0); // pie, sin cabecera
        ape.writeBytes(new byte[8]);
        return ape.toByteArray();
    }

    private static byte[] id3v1(String texto) {
        byte[] t = new byte[128];
        System.arraycopy(ascii("TAG"), 0, t, 0, 3);
        byte[] c = ascii(texto);
        System.arraycopy(c, 0, t, 33, Math.min(c.length, 90));
        return t;
    }

    @Test
    void elMp3ConservaSoloLasTramasDeAudio() {
        ByteArrayOutputStream mp3 = new ByteArrayOutputStream();
        ByteArrayOutputStream esperado = new ByteArrayOutputStream();
        mp3.writeBytes(id3v2(GPS));
        for (int n = 1; n <= 3; n++) {
            mp3.writeBytes(tramaMp3(n));
            esperado.writeBytes(tramaMp3(n));
        }
        mp3.writeBytes(id3v2("en medio " + GPS)); // una etiqueta escondida entre tramas
        for (int n = 4; n <= 6; n++) {
            mp3.writeBytes(tramaMp3(n));
            esperado.writeBytes(tramaMp3(n));
        }
        mp3.writeBytes(apeConPie(GPS));
        mp3.writeBytes(id3v1(GPS));
        byte[] original = mp3.toByteArray();
        assertThat(TipoArchivoEvidencia.detectar(original)).contains(TipoArchivoEvidencia.MP3);

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.MP3, original);

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        assertThat(limpio).isEqualTo(esperado.toByteArray());
    }

    @Test
    void unMp3SinTramasSeRechaza() {
        byte[] soloEtiqueta = id3v2(GPS);

        assertThatThrownBy(() -> LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.MP3, soloEtiqueta))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    // ------------------------------------------------------------------------------------------------ FLAC

    private static void bloqueFlac(ByteArrayOutputStream s, int tipo, boolean ultimo, byte[] datos) {
        s.write(tipo | (ultimo ? 0x80 : 0));
        s.write(datos.length >> 16);
        s.write(datos.length >> 8);
        s.write(datos.length);
        s.writeBytes(datos);
    }

    @Test
    void elFlacConservaStreaminfoYLasTramas() {
        byte[] streaminfo = new byte[34];
        Arrays.fill(streaminfo, (byte) 7);
        byte[] tramas = new byte[300];
        tramas[0] = (byte) 0xFF;
        tramas[1] = (byte) 0xF8;
        Arrays.fill(tramas, 2, tramas.length, (byte) 3);
        ByteArrayOutputStream flac = new ByteArrayOutputStream();
        flac.writeBytes(ascii("fLaC"));
        bloqueFlac(flac, 0, false, streaminfo);
        bloqueFlac(flac, 4, false, ascii("\6\0\0\0BioHub\1\0\0\0" + " \0\0\0LOCATION=" + GPS)); // VORBIS_COMMENT
        bloqueFlac(flac, 1, false, new byte[64]); // PADDING
        bloqueFlac(flac, 6, true, ascii("imagen " + GPS)); // PICTURE
        flac.writeBytes(tramas);
        flac.writeBytes(id3v1(GPS));
        byte[] original = flac.toByteArray();

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.FLAC, original);

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        ByteArrayOutputStream esperado = new ByteArrayOutputStream();
        esperado.writeBytes(ascii("fLaC"));
        bloqueFlac(esperado, 0, true, streaminfo); // ahora es el último bloque de metadatos
        esperado.writeBytes(tramas);
        assertThat(limpio).isEqualTo(esperado.toByteArray());
    }

    // ------------------------------------------------------------------------------------------------ OGG

    private record PaginaLeida(int tipo, long granulo, int serie, int secuencia, List<Integer> segmentos, byte[] datos) {
    }

    /** Pagina los paquetes como lo haría un codificador: cada grupo de paquetes cierra su página. */
    private static void paginar(ByteArrayOutputStream s, int serie, int[] secuencia, int tipoPrimera, long granulo,
                                byte[]... paquetes) {
        List<Integer> segmentos = new ArrayList<>();
        ByteArrayOutputStream datos = new ByteArrayOutputStream();
        for (byte[] p : paquetes) {
            int r = p.length;
            while (r >= 255) {
                segmentos.add(255);
                r -= 255;
            }
            segmentos.add(r);
            datos.writeBytes(p);
        }
        byte[] todo = datos.toByteArray();
        int pos = 0;
        boolean continua = false;
        for (int i = 0; i < segmentos.size(); i += 255) {
            List<Integer> trozo = segmentos.subList(i, Math.min(i + 255, segmentos.size()));
            int largo = trozo.stream().mapToInt(Integer::intValue).sum();
            boolean ultima = i + 255 >= segmentos.size();
            int tipo = (continua ? 1 : 0) | (i == 0 ? tipoPrimera : 0);
            pagina(s, tipo, ultima ? granulo : -1, serie, secuencia[0]++, trozo, Arrays.copyOfRange(todo, pos, pos + largo));
            pos += largo;
            continua = trozo.get(trozo.size() - 1) == 255;
        }
    }

    private static void pagina(ByteArrayOutputStream s, int tipo, long granulo, int serie, int secuencia,
                               List<Integer> segmentos, byte[] datos) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.writeBytes(ascii("OggS"));
        p.write(0);
        p.write(tipo);
        for (int k = 0; k < 8; k++) {
            p.write((int) (granulo >>> (8 * k)) & 0xFF);
        }
        le32(p, serie);
        le32(p, secuencia);
        le32(p, 0);
        p.write(segmentos.size());
        segmentos.forEach(p::write);
        p.writeBytes(datos);
        byte[] b = p.toByteArray();
        long crc = LimpiadorAudio.crcOgg(0, b, 0, b.length);
        for (int k = 0; k < 4; k++) {
            b[22 + k] = (byte) (crc >>> (8 * k));
        }
        s.writeBytes(b);
    }

    private static List<PaginaLeida> leer(byte[] d) {
        List<PaginaLeida> r = new ArrayList<>();
        int i = 0;
        while (i < d.length) {
            assertThat(new String(d, i, 4, StandardCharsets.ISO_8859_1)).isEqualTo("OggS");
            int n = d[i + 26] & 0xFF;
            List<Integer> seg = new ArrayList<>();
            int total = 0;
            for (int k = 0; k < n; k++) {
                seg.add(d[i + 27 + k] & 0xFF);
                total += d[i + 27 + k] & 0xFF;
            }
            byte[] cabecera = Arrays.copyOfRange(d, i, i + 27 + n + total);
            long crcLeido = (cabecera[22] & 0xFFL) | (cabecera[23] & 0xFFL) << 8 | (cabecera[24] & 0xFFL) << 16
                    | (cabecera[25] & 0xFFL) << 24;
            cabecera[22] = cabecera[23] = cabecera[24] = cabecera[25] = 0;
            assertThat(LimpiadorAudio.crcOgg(0, cabecera, 0, cabecera.length)).as("CRC de la página").isEqualTo(crcLeido);
            long granulo = 0;
            for (int k = 7; k >= 0; k--) {
                granulo = (granulo << 8) | (d[i + 6 + k] & 0xFF);
            }
            int serie = (d[i + 14] & 0xFF) | (d[i + 15] & 0xFF) << 8 | (d[i + 16] & 0xFF) << 16 | (d[i + 17] & 0xFF) << 24;
            int secuencia = (d[i + 18] & 0xFF) | (d[i + 19] & 0xFF) << 8 | (d[i + 20] & 0xFF) << 16 | (d[i + 21] & 0xFF) << 24;
            r.add(new PaginaLeida(d[i + 5] & 0xFF, granulo, serie, secuencia, seg,
                    Arrays.copyOfRange(d, i + 27 + n, i + 27 + n + total)));
            i += 27 + n + total;
        }
        return r;
    }

    private static List<byte[]> paquetes(List<PaginaLeida> paginas) {
        List<byte[]> r = new ArrayList<>();
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        for (PaginaLeida p : paginas) {
            int pos = 0;
            for (int largo : p.segmentos()) {
                actual.write(p.datos(), pos, largo);
                pos += largo;
                if (largo < 255) {
                    r.add(actual.toByteArray());
                    actual.reset();
                }
            }
        }
        return r;
    }

    private static byte[] relleno(int largo, int valor) {
        byte[] b = new byte[largo];
        Arrays.fill(b, (byte) valor);
        return b;
    }

    @Test
    void elOggVorbisQuedaConComentariosVaciosYElAudioIntacto() {
        byte[] identificacion = new byte[30];
        identificacion[0] = 1;
        System.arraycopy(ascii("vorbis"), 0, identificacion, 1, 6);
        // Comentarios enormes (más de una página) con la ubicación, como los de algunas grabadoras.
        ByteArrayOutputStream comentarios = new ByteArrayOutputStream();
        comentarios.write(3);
        comentarios.writeBytes(ascii("vorbis"));
        le32(comentarios, 11);
        comentarios.writeBytes(ascii("Grabadora 9"));
        le32(comentarios, 2);
        byte[] c1 = ascii("LOCATION=" + GPS);
        le32(comentarios, c1.length);
        comentarios.writeBytes(c1);
        byte[] c2 = ascii("COMMENT=" + "x".repeat(70_000) + GPS);
        le32(comentarios, c2.length);
        comentarios.writeBytes(c2);
        comentarios.write(1);
        byte[] configuracion = relleno(3000, 5);
        configuracion[0] = 5;
        byte[] audio1 = relleno(400, 11);
        byte[] audio2 = relleno(1200, 12);
        byte[] audio3 = relleno(90, 13);

        ByteArrayOutputStream ogg = new ByteArrayOutputStream();
        int[] seq = {0};
        int serie = 0x1234ABCD;
        paginar(ogg, serie, seq, 0x02, 0, identificacion);
        paginar(ogg, serie, seq, 0, 0, comentarios.toByteArray(), configuracion);
        paginar(ogg, serie, seq, 0, 4096, audio1, audio2);
        paginar(ogg, serie, seq, 0x04, 8192, audio3);
        ogg.writeBytes(ascii("basura al final " + GPS));
        byte[] original = ogg.toByteArray();
        assertThat(contiene(original, "GPSLatitude")).isTrue();

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.OGG, original);

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        assertThat(contiene(limpio, "Grabadora")).isFalse();
        List<PaginaLeida> paginas = leer(limpio);
        for (int k = 0; k < paginas.size(); k++) {
            assertThat(paginas.get(k).secuencia()).as("páginas numeradas en orden").isEqualTo(k);
            assertThat(paginas.get(k).serie()).isEqualTo(serie);
        }
        assertThat(paginas.get(0).tipo() & 0x02).as("primera página BOS").isEqualTo(2);
        assertThat(paginas.get(paginas.size() - 1).tipo() & 0x04).as("última página EOS").isEqualTo(4);
        assertThat(paginas.get(paginas.size() - 1).granulo()).isEqualTo(8192);
        List<byte[]> p = paquetes(paginas);
        assertThat(p).hasSize(6);
        assertThat(p.get(0)).isEqualTo(identificacion);
        assertThat(new String(p.get(1), StandardCharsets.ISO_8859_1)).isEqualTo("\3vorbis\6\0\0\0BioHub\0\0\0\0\1");
        assertThat(p.get(2)).isEqualTo(configuracion);
        assertThat(p.get(3)).isEqualTo(audio1);
        assertThat(p.get(4)).isEqualTo(audio2);
        assertThat(p.get(5)).isEqualTo(audio3);
    }

    @Test
    void elOggOpusQuedaConComentariosVacios() {
        byte[] cabecera = ascii("OpusHead\1\2\0\0\u0080»\0\0\0\0\0");
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        tags.writeBytes(ascii("OpusTags"));
        le32(tags, 3);
        tags.writeBytes(ascii("abc"));
        le32(tags, 1);
        byte[] c = ascii("LOCATION=" + GPS);
        le32(tags, c.length);
        tags.writeBytes(c);
        byte[] audio = relleno(500, 9);
        ByteArrayOutputStream ogg = new ByteArrayOutputStream();
        int[] seq = {0};
        paginar(ogg, 77, seq, 0x02, 0, cabecera);
        paginar(ogg, 77, seq, 0, 0, tags.toByteArray());
        paginar(ogg, 77, seq, 0x04, 960, audio);

        byte[] limpio = LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.OGG, ogg.toByteArray());

        assertThat(contiene(limpio, "GPSLatitude")).isFalse();
        List<byte[]> p = paquetes(leer(limpio));
        assertThat(new String(p.get(1), StandardCharsets.ISO_8859_1)).isEqualTo("OpusTags\6\0\0\0BioHub\0\0\0\0");
        assertThat(p.get(2)).isEqualTo(audio);
    }

    @Test
    void unOggConCrcAlteradoOConDosPistasSeRechaza() {
        byte[] identificacion = ascii("OpusHead\1\2\0\0\u0080»\0\0\0\0\0");
        ByteArrayOutputStream ogg = new ByteArrayOutputStream();
        int[] seq = {0};
        paginar(ogg, 1, seq, 0x02, 0, identificacion);
        paginar(ogg, 1, seq, 0, 0, ascii("OpusTags\0\0\0\0\0\0\0\0"));
        paginar(ogg, 2, seq, 0x02, 0, identificacion); // otra pista
        assertThatThrownBy(() -> LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.OGG, ogg.toByteArray()))
                .isInstanceOf(DatosHallazgoInvalidosException.class);

        byte[] alterado = ogg.toByteArray();
        alterado[40] ^= 1;
        assertThatThrownBy(() -> LimpiadorMetadatos.limpiar(TipoArchivoEvidencia.OGG, alterado))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
    }
}
