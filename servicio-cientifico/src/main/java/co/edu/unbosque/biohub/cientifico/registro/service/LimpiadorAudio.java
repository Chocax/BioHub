package co.edu.unbosque.biohub.cientifico.registro.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Quita los metadatos de las grabaciones de audio antes de guardarlas (RNF9, ASR6).
 *
 * <p>Las grabadoras de los celulares y los editores de audio guardan etiquetas de texto (título, fecha, notas, y en
 * algunos casos la ubicación). En vez de buscar y borrar etiquetas conocidas, se hace al revés: el archivo se
 * reconstruye <b>solo con el sonido</b> y lo mínimo para reproducirlo. Todo lo demás se descarta, aunque sea un
 * bloque que hoy no conocemos.
 *
 * <ul>
 *   <li><b>WAV:</b> se conservan solo los bloques {@code fmt }, {@code fact} y {@code data}. Se descartan
 *       {@code LIST/INFO}, {@code bext}, {@code iXML}, {@code id3 }, etc.</li>
 *   <li><b>MP3:</b> se recorren las tramas de audio una por una y solo se copian ellas. Las etiquetas ID3v1, ID3v2,
 *       APEv2 y Lyrics3, en cualquier posición, quedan fuera.</li>
 *   <li><b>FLAC:</b> se conservan los bloques {@code STREAMINFO} y {@code SEEKTABLE}; se descartan los comentarios
 *       Vorbis, las imágenes y el resto. Se quitan también las etiquetas pegadas al final.</li>
 *   <li><b>OGG</b> (Vorbis u Opus, una sola pista): el paquete de comentarios se reemplaza por uno vacío y las
 *       páginas se vuelven a numerar y a firmar (CRC). El sonido se copia tal cual.</li>
 * </ul>
 * Un archivo que no se puede recorrer completo se rechaza: no se guarda algo que no se pudo revisar.
 */
final class LimpiadorAudio {

    /** Texto que queda como "vendor" en los comentarios OGG: no identifica la grabadora ni el teléfono. */
    static final String VENDOR = "BioHub";

    private LimpiadorAudio() {
    }

    // ---------------------------------------------------------------------------------------------------- WAV

    static byte[] wav(byte[] d) {
        if (d.length < 12 || !ascii(d, 0, "RIFF") || !ascii(d, 8, "WAVE")) {
            throw LimpiadorMetadatos.invalido();
        }
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream(d.length);
        boolean fmt = false;
        boolean data = false;
        int i = 12;
        while (i + 8 <= d.length) {
            String id = new String(d, i, 4, StandardCharsets.US_ASCII);
            long largo = leUint32(d, i + 4);
            long disponible = d.length - (i + 8L);
            if (largo > disponible) {
                // Algunas grabadoras dejan el tamaño de "data" sin actualizar: se toma lo que hay.
                if (!"data".equals(id)) {
                    throw LimpiadorMetadatos.invalido();
                }
                largo = disponible;
            }
            int total = (int) largo + 8;
            if ("fmt ".equals(id) || "fact".equals(id) || "data".equals(id)) {
                cuerpo.write(d, i, 4);
                escribirLeUint32(cuerpo, largo);
                cuerpo.write(d, i + 8, (int) largo);
                if ((largo & 1) == 1) {
                    cuerpo.write(0); // los bloques RIFF van alineados a 2 bytes
                }
                fmt |= "fmt ".equals(id);
                data |= "data".equals(id);
            }
            i = (int) Math.min(d.length, (long) total + i + (largo & 1));
        }
        if (!fmt || !data) {
            throw LimpiadorMetadatos.invalido();
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream(cuerpo.size() + 12);
        salida.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        escribirLeUint32(salida, cuerpo.size() + 4L);
        salida.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        salida.writeBytes(cuerpo.toByteArray());
        return salida.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------------- MP3

    private static final int[][] KBPS = {
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}, // MPEG1 capa I
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},    // MPEG1 capa II
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},     // MPEG1 capa III
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},    // MPEG2/2.5 capa I
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},         // MPEG2/2.5 capas II y III
    };
    private static final int[][] HZ = {{11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}};

    static byte[] mp3(byte[] original) {
        // Primero las etiquetas pegadas al final (ID3v1, APEv2 sin cabecera): su texto no se recorre como audio.
        byte[] d = Arrays.copyOf(original, finSinEtiquetasAlFinal(original, 0));
        ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length);
        int tramas = 0;
        int i = 0;
        while (i < d.length) {
            int etiqueta = largoEtiqueta(d, i);
            if (etiqueta > 0) {
                i += etiqueta;
                continue;
            }
            int trama = largoTrama(d, i);
            if (trama > 0 && i + trama <= d.length && (i + trama == d.length || siguienteEsValido(d, i + trama))) {
                salida.write(d, i, trama);
                tramas++;
                i += trama;
            } else {
                i++; // basura entre tramas o una trama cortada al final: no se copia
            }
        }
        if (tramas == 0) {
            throw LimpiadorMetadatos.invalido();
        }
        return salida.toByteArray();
    }

    /** Lo que sigue a una trama debe ser otra trama, una etiqueta o el final; si no, el "encabezado" era casual. */
    private static boolean siguienteEsValido(byte[] d, int i) {
        return largoEtiqueta(d, i) > 0 || largoTrama(d, i) > 0;
    }

    /** Largo de la etiqueta (ID3v2, ID3v1, APEv2, Lyrics3) que empieza en {@code i}, o 0 si no hay ninguna. */
    private static int largoEtiqueta(byte[] d, int i) {
        if (ascii(d, i, "ID3") && i + 10 <= d.length) {
            int tam = ((d[i + 6] & 0x7F) << 21) | ((d[i + 7] & 0x7F) << 14) | ((d[i + 8] & 0x7F) << 7) | (d[i + 9] & 0x7F);
            int pie = (d[i + 5] & 0x10) != 0 ? 10 : 0;
            return (int) Math.min((long) d.length - i, 10L + tam + pie);
        }
        if (ascii(d, i, "3DI") && i + 10 <= d.length) {
            return 10; // pie de una ID3v2.4 cuyo contenido ya se saltó con su cabecera
        }
        if (ascii(d, i, "APETAGEX") && i + 32 <= d.length) {
            long tam = leUint32(d, i + 12);
            boolean esCabecera = (d[i + 23] & 0x20) != 0;
            // La cabecera va al principio de la etiqueta; el pie, al final: en ambos casos se salta lo que cubre.
            return (int) Math.min((long) d.length - i, esCabecera ? tam + 32 : 32);
        }
        if (ascii(d, i, "TAG") && i + 128 <= d.length) {
            return ascii(d, i, "TAG+") && i + 227 <= d.length ? 227 : 128;
        }
        if (ascii(d, i, "LYRICSBEGIN")) {
            int fin = indice(d, i, "LYRICS200");
            int fin1 = indice(d, i, "LYRICSEND");
            int cierre = fin >= 0 ? fin + 9 : fin1 >= 0 ? fin1 + 9 : d.length;
            return cierre - i;
        }
        return 0;
    }

    /** Largo de la trama MPEG de audio que empieza en {@code i}, o 0 si ahí no hay un encabezado válido. */
    private static int largoTrama(byte[] d, int i) {
        if (i + 4 > d.length || (d[i] & 0xFF) != 0xFF || (d[i + 1] & 0xE0) != 0xE0) {
            return 0;
        }
        int version = (d[i + 1] >> 3) & 3;  // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        int capa = (d[i + 1] >> 1) & 3;     // 3 = I, 2 = II, 1 = III
        int bitrate = (d[i + 2] >> 4) & 0xF;
        int frecuencia = (d[i + 2] >> 2) & 3;
        int relleno = (d[i + 2] >> 1) & 1;
        if (version == 1 || capa == 0 || bitrate == 0 || bitrate == 15 || frecuencia == 3) {
            return 0; // reservado o "formato libre" (no se puede medir la trama)
        }
        int hz = HZ[version][frecuencia];
        int tabla = version == 3 ? 3 - capa : (capa == 3 ? 3 : 4);
        long bps = KBPS[tabla][bitrate] * 1000L;
        if (capa == 3) {
            return (int) ((12 * bps / hz + relleno) * 4);
        }
        int factor = capa == 1 && version != 3 ? 72 : 144;
        return (int) (factor * bps / hz + relleno);
    }

    // ---------------------------------------------------------------------------------------------------- FLAC

    private static final int STREAMINFO = 0;
    private static final int SEEKTABLE = 3;

    static byte[] flac(byte[] d) {
        if (d.length < 8 || !ascii(d, 0, "fLaC")) {
            throw LimpiadorMetadatos.invalido();
        }
        List<int[]> conservados = new ArrayList<>(); // {inicio, largo total} de cada bloque que se queda
        int i = 4;
        boolean ultimo = false;
        while (!ultimo) {
            if (i + 4 > d.length) {
                throw LimpiadorMetadatos.invalido();
            }
            ultimo = (d[i] & 0x80) != 0;
            int tipo = d[i] & 0x7F;
            int largo = ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (i + 4 + largo > d.length) {
                throw LimpiadorMetadatos.invalido();
            }
            if (tipo == STREAMINFO || tipo == SEEKTABLE) {
                conservados.add(new int[] {i, largo + 4});
            }
            i += largo + 4;
        }
        if (conservados.isEmpty() || (d[conservados.get(0)[0]] & 0x7F) != STREAMINFO) {
            throw LimpiadorMetadatos.invalido();
        }
        int finAudio = finSinEtiquetasAlFinal(d, i);
        if (finAudio - i < 2 || (d[i] & 0xFF) != 0xFF || (d[i + 1] & 0xFE) != 0xF8) {
            throw LimpiadorMetadatos.invalido(); // después de los metadatos tiene que venir una trama FLAC
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length);
        salida.writeBytes("fLaC".getBytes(StandardCharsets.US_ASCII));
        for (int k = 0; k < conservados.size(); k++) {
            int[] b = conservados.get(k);
            boolean esUltimo = k == conservados.size() - 1;
            salida.write((d[b[0]] & 0x7F) | (esUltimo ? 0x80 : 0));
            salida.write(d, b[0] + 1, b[1] - 1);
        }
        salida.write(d, i, finAudio - i);
        return salida.toByteArray();
    }

    /** Fin de los datos de audio sin las etiquetas ID3v1 o APEv2 que algunos programas pegan al final. */
    private static int finSinEtiquetasAlFinal(byte[] d, int desde) {
        int fin = d.length;
        boolean cambio = true;
        while (cambio) {
            cambio = false;
            if (fin - desde >= 128 && ascii(d, fin - 128, "TAG")) {
                fin -= 128;
                cambio = true;
            }
            if (fin - desde >= 32 && ascii(d, fin - 32, "APETAGEX")) {
                long tam = leUint32(d, fin - 32 + 12);
                boolean conCabecera = (d[fin - 32 + 23] & 0x80) != 0;
                long quitar = tam + (conCabecera ? 32 : 0);
                if (quitar <= fin - desde) {
                    fin -= (int) quitar;
                    cambio = true;
                }
            }
        }
        return fin;
    }

    // ---------------------------------------------------------------------------------------------------- OGG

    private record Pagina(int tipo, long granulo, int serie, List<Integer> segmentos, byte[] datos) {
    }

    static byte[] ogg(byte[] d) {
        List<Pagina> paginas = paginasOgg(d);
        int serie = paginas.get(0).serie();
        if (paginas.stream().anyMatch(p -> p.serie() != serie)) {
            throw new DatosHallazgoInvalidosException("archivo", "El OGG tiene más de una pista; sube una sola grabación.");
        }

        // Paquetes de cabecera: Vorbis tiene 3 (identificación, comentarios, configuración) y Opus 2.
        List<byte[]> cabeceras = new ArrayList<>();
        ByteArrayOutputStream paquete = new ByteArrayOutputStream();
        int necesarios = 0;
        int primeraDeAudio = -1;
        for (int p = 0; p < paginas.size() && primeraDeAudio < 0; p++) {
            Pagina pagina = paginas.get(p);
            int pos = 0;
            for (int s = 0; s < pagina.segmentos().size(); s++) {
                int largo = pagina.segmentos().get(s);
                paquete.write(pagina.datos(), pos, largo);
                pos += largo;
                if (largo < 255) {
                    cabeceras.add(paquete.toByteArray());
                    paquete.reset();
                    if (cabeceras.size() == 1) {
                        necesarios = cabecerasNecesarias(cabeceras.get(0));
                    }
                    if (cabeceras.size() == necesarios) {
                        // La primera trama de audio empieza siempre en una página nueva (especificación Vorbis y Opus).
                        if (s != pagina.segmentos().size() - 1) {
                            throw LimpiadorMetadatos.invalido();
                        }
                        primeraDeAudio = p + 1;
                        break;
                    }
                }
            }
        }
        if (primeraDeAudio < 0) {
            throw LimpiadorMetadatos.invalido();
        }
        cabeceras.set(1, comentariosVacios(cabeceras.get(0), cabeceras.get(1)));

        ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length);
        int secuencia = 0;
        // Página 1: solo el paquete de identificación (se reescribe igual, por si traía basura).
        escribirPagina(salida, 0x02, 0, serie, secuencia++, lacing(List.of(cabeceras.get(0))), cabeceras.get(0));
        // Comentarios (vacíos) y, en Vorbis, configuración: en tantas páginas como hagan falta.
        List<byte[]> resto = cabeceras.subList(1, cabeceras.size());
        secuencia = escribirPaquetesEnPaginas(salida, resto, serie, secuencia);
        // Audio: las páginas se copian con su contenido, solo cambia el número de página y el CRC.
        for (int p = primeraDeAudio; p < paginas.size(); p++) {
            Pagina pagina = paginas.get(p);
            escribirPagina(salida, pagina.tipo() & ~0x02, pagina.granulo(), serie, secuencia++, pagina.segmentos(),
                    pagina.datos());
        }
        return salida.toByteArray();
    }

    private static int cabecerasNecesarias(byte[] identificacion) {
        if (identificacion.length >= 7 && identificacion[0] == 0x01 && ascii(identificacion, 1, "vorbis")) {
            return 3;
        }
        if (ascii(identificacion, 0, "OpusHead")) {
            return 2;
        }
        throw new DatosHallazgoInvalidosException("archivo", "Solo se admite audio OGG con Vorbis u Opus.");
    }

    /** Paquete de comentarios sin ninguna etiqueta, en el formato del códec de la pista. */
    private static byte[] comentariosVacios(byte[] identificacion, byte[] original) {
        boolean vorbis = identificacion[0] == 0x01;
        if (vorbis ? !(original.length >= 7 && original[0] == 0x03 && ascii(original, 1, "vorbis"))
                : !ascii(original, 0, "OpusTags")) {
            throw LimpiadorMetadatos.invalido();
        }
        byte[] vendor = VENDOR.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        if (vorbis) {
            c.write(0x03);
            c.writeBytes("vorbis".getBytes(StandardCharsets.US_ASCII));
        } else {
            c.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
        }
        escribirLeUint32(c, vendor.length);
        c.writeBytes(vendor);
        escribirLeUint32(c, 0); // cero comentarios
        if (vorbis) {
            c.write(0x01); // bit de cierre
        }
        return c.toByteArray();
    }

    private static List<Pagina> paginasOgg(byte[] d) {
        List<Pagina> paginas = new ArrayList<>();
        int i = 0;
        while (i < d.length) {
            if (i + 27 > d.length || !ascii(d, i, "OggS") || d[i + 4] != 0) {
                if (paginas.isEmpty()) {
                    throw LimpiadorMetadatos.invalido();
                }
                break; // lo que venga después de la última página no es audio: no se copia
            }
            int tipo = d[i + 5] & 0xFF;
            long granulo = 0;
            for (int k = 7; k >= 0; k--) {
                granulo = (granulo << 8) | (d[i + 6 + k] & 0xFF);
            }
            int serie = (int) leUint32(d, i + 14);
            int nSegmentos = d[i + 26] & 0xFF;
            if (i + 27 + nSegmentos > d.length) {
                throw LimpiadorMetadatos.invalido();
            }
            List<Integer> segmentos = new ArrayList<>(nSegmentos);
            int total = 0;
            for (int s = 0; s < nSegmentos; s++) {
                int largo = d[i + 27 + s] & 0xFF;
                segmentos.add(largo);
                total += largo;
            }
            int inicio = i + 27 + nSegmentos;
            if (inicio + total > d.length) {
                throw LimpiadorMetadatos.invalido();
            }
            byte[] encabezado = Arrays.copyOfRange(d, i, inicio);
            encabezado[22] = encabezado[23] = encabezado[24] = encabezado[25] = 0;
            long esperado = crcOgg(crcOgg(0, encabezado, 0, encabezado.length), d, inicio, total);
            if (esperado != leUint32(d, i + 22)) {
                throw LimpiadorMetadatos.invalido();
            }
            paginas.add(new Pagina(tipo, granulo, serie, segmentos, Arrays.copyOfRange(d, inicio, inicio + total)));
            i = inicio + total;
        }
        return paginas;
    }

    /** Tabla de lacing de los paquetes: cada uno en trozos de 255 bytes y un último trozo menor (puede ser 0). */
    private static List<Integer> lacing(List<byte[]> paquetes) {
        List<Integer> segmentos = new ArrayList<>();
        for (byte[] p : paquetes) {
            int restante = p.length;
            while (restante >= 255) {
                segmentos.add(255);
                restante -= 255;
            }
            segmentos.add(restante);
        }
        return segmentos;
    }

    private static int escribirPaquetesEnPaginas(ByteArrayOutputStream salida, List<byte[]> paquetes, int serie,
                                                 int secuencia) {
        List<Integer> segmentos = lacing(paquetes);
        ByteArrayOutputStream todo = new ByteArrayOutputStream();
        paquetes.forEach(todo::writeBytes);
        byte[] datos = todo.toByteArray();
        int pos = 0;
        boolean continua = false;
        for (int s = 0; s < segmentos.size(); s += 255) {
            List<Integer> trozo = segmentos.subList(s, Math.min(s + 255, segmentos.size()));
            int largo = trozo.stream().mapToInt(Integer::intValue).sum();
            boolean terminaPaquete = trozo.get(trozo.size() - 1) < 255;
            // Las cabeceras tienen granulo 0; una página donde no termina ningún paquete lleva -1.
            boolean algunoTermina = trozo.stream().anyMatch(x -> x < 255);
            escribirPagina(salida, continua ? 0x01 : 0, algunoTermina ? 0 : -1, serie, secuencia++, trozo,
                    Arrays.copyOfRange(datos, pos, pos + largo));
            pos += largo;
            continua = !terminaPaquete;
        }
        return secuencia;
    }

    private static void escribirPagina(ByteArrayOutputStream salida, int tipo, long granulo, int serie, int secuencia,
                                       List<Integer> segmentos, byte[] datos) {
        ByteArrayOutputStream p = new ByteArrayOutputStream(27 + segmentos.size() + datos.length);
        p.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII));
        p.write(0);
        p.write(tipo);
        for (int k = 0; k < 8; k++) {
            p.write((int) (granulo >>> (8 * k)) & 0xFF);
        }
        escribirLeUint32(p, serie & 0xFFFFFFFFL);
        escribirLeUint32(p, secuencia);
        escribirLeUint32(p, 0); // CRC, se calcula abajo
        p.write(segmentos.size());
        segmentos.forEach(p::write);
        p.writeBytes(datos);
        byte[] pagina = p.toByteArray();
        long crc = crcOgg(0, pagina, 0, pagina.length);
        pagina[22] = (byte) crc;
        pagina[23] = (byte) (crc >>> 8);
        pagina[24] = (byte) (crc >>> 16);
        pagina[25] = (byte) (crc >>> 24);
        salida.writeBytes(pagina);
    }

    private static final int[] TABLA_CRC = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int r = n << 24;
            for (int k = 0; k < 8; k++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            TABLA_CRC[n] = r;
        }
    }

    /** CRC-32 de OGG: polinomio 0x04C11DB7, sin reflejar, valor inicial 0 y sin XOR final. */
    static long crcOgg(long crc, byte[] d, int desde, int largo) {
        int c = (int) crc;
        for (int k = desde; k < desde + largo; k++) {
            c = (c << 8) ^ TABLA_CRC[((c >>> 24) ^ d[k]) & 0xFF];
        }
        return c & 0xFFFFFFFFL;
    }

    // ---------------------------------------------------------------------------------------------------- comunes

    private static boolean ascii(byte[] d, int i, String texto) {
        if (i < 0 || i + texto.length() > d.length) {
            return false;
        }
        for (int k = 0; k < texto.length(); k++) {
            if (d[i + k] != (byte) texto.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int indice(byte[] d, int desde, String texto) {
        for (int i = desde; i + texto.length() <= d.length; i++) {
            if (ascii(d, i, texto)) {
                return i;
            }
        }
        return -1;
    }

    private static long leUint32(byte[] d, int i) {
        return (d[i] & 0xFFL) | (d[i + 1] & 0xFFL) << 8 | (d[i + 2] & 0xFFL) << 16 | (d[i + 3] & 0xFFL) << 24;
    }

    private static void escribirLeUint32(ByteArrayOutputStream s, long v) {
        s.write((int) v & 0xFF);
        s.write((int) (v >>> 8) & 0xFF);
        s.write((int) (v >>> 16) & 0xFF);
        s.write((int) (v >>> 24) & 0xFF);
    }
}
