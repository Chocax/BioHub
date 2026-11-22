package co.edu.unbosque.biohub.consultapublica.eventos.service;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detecta coordenadas geográficas en un evento {@code hallazgo.*}, tanto por el NOMBRE de una clave como por el
 * TEXTO libre de un valor (SEC-J-02, SEC-J-03; D14: el portal público nunca lleva coordenadas).
 *
 * <p>Es una defensa en profundidad: la fuente (registro) debe impedir que se escriban. Tiene que ser conservadora
 * con los falsos negativos pero no bloquear texto biológico habitual: fechas ("2026-03-14"), medidas
 * ("12,5 cm"), rangos de altitud ("3.200, 3.500 msnm") y cantidades no se consideran coordenadas.
 *
 * <p><b>Normalización previa (SEC-I27-1):</b> antes de buscar se normaliza el texto (NFKC, dígitos Unicode, espacios
 * duros, guiones Unicode, ancho cero). Detecta, además de los decimales con signo, hemisferio detrás o delante
 * (formato Garmin "N05 31.928 W073 22.027"), GMS sin símbolos ("5 31 55 N"), par sin signo de Colombia
 * ("5.532, 73.367"), hemisferio en palabras ("5.53214 norte"), coordenadas planas ("Norte 1012345 Este 1045678") y UTM.
 * SEC-I27g-1 añade: rachas de espacios o saltos de línea con sangría entre los dos números, hemisferio en minúscula
 * ("5 31 55 n", "n05 31.928 w073 22.027"), GMS sin grado ("5 31' 55\"", "05:31:55 N"), par con la longitud primero
 * ("POINT(-73.367 5.532)") y par unido por "y" ("5.53214 y -73.36712"). Quedan para otra iteración UTM con zona
 * separada, MGRS, Plus Codes, enlaces de mapas y los trucos con caracteres Unicode invisibles (SEC-I27g-3).
 *
 * <p>Falsos positivos conocidos: "long. 12 cm", "lat 3" o "lon 5" (abreviaturas de longitud/latitud seguidas de un
 * número); "talla 5 12 N" y otros "número número letra N/S/E/O/W" en mayúscula; "N 12 3.5" (letra de hemisferio
 * delante de dos números, también en minúscula: "n 12 3.5"); "N 12.5000"; un par con miles y 3 decimales dentro de rango, como "5.500, 73.000
 * individuos" o, con la longitud primero, "70.250, 3.100 g"; "al norte 1.2500 km"; y "este 1.000.000" o "N 1.250.000" (cantidades de 7 cifras tras la palabra
 * "este"/"norte" o una N/E suelta).
 *
 * <p>Esta clase existe en dos copias (servicio-cientifico y servicio-consulta-publica, D4). El bloque entre
 * {@code <reglas-compartidas>} y {@code </reglas-compartidas>} debe ser idéntico; lo vigila
 * {@code DetectorCoordenadasParidadTest}. Fuera del bloque solo está el análisis en bruto, propio de esta copia.
 *
 * <p>Todos los cuantificadores de los patrones están acotados para que un texto hostil no provoque
 * retrocesos excesivos. Esta clase nunca devuelve ni registra el texto analizado.
 */
public final class DetectorCoordenadas {

    // <reglas-compartidas>
    // Este bloque debe ser IDÉNTICO en servicio-cientifico y servicio-consulta-publica (D4: no hay módulo común).
    // La prueba DetectorCoordenadasParidadTest lo comprueba con el corpus y con un resumen del bloque (SEC-I27-1).

    /** Claves prohibidas en minúsculas (D14, contrato 04 §7.3). */
    static final Set<String> CLAVES_PROHIBIDAS = Set.of("latitud", "longitud", "lat", "lon", "lng", "latitude",
            "longitude", "coordenadas", "coordinates", "geometry", "geom", "ubicacionexacta");

    private static final String SEP = "(?:\\s{0,3}[,;/|]\\s{0,3}|\\s{1,3})";
    private static final String NEG = "[-+−–]";

    /** Par decimal: el segundo con signo (la longitud de Colombia es negativa) o ambos con 4 o más decimales. */
    private static final Pattern DECIMAL_CON_SIGNO = Pattern.compile(
            "[-+−–]?\\d{1,3}[.,]\\d{3,}" + SEP + NEG + "\\d{1,3}[.,]\\d{3,}");
    /** Igual, con espacio entre el signo y el número ("5.53214, - 73.36712"); exige 4 decimales para no marcar "3.200 - 3.500 msnm". */
    private static final Pattern DECIMAL_SIGNO_SEPARADO = Pattern.compile(
            "[-+]?\\d{1,3}[.,]\\d{4,}" + SEP + NEG + "\\s{1,2}\\d{1,3}[.,]\\d{4,}");
    private static final Pattern DECIMAL_PRECISO = Pattern.compile(
            "[-+−–]?\\d{1,3}[.,]\\d{4,}" + SEP + "[-+−–]?\\d{1,3}[.,]\\d{4,}");
    /** Grados decimales con hemisferio en mayúscula (así evita "1.500 o 2.000"): "5.53214 N", "73.3671°W", "5,5°N". */
    private static final Pattern DECIMAL_HEMISFERIO = Pattern.compile(
            "\\d{1,3}(?:[.,]\\d{3,}\\s{0,2}[°º˚]?|[.,]\\d+\\s{0,2}[°º˚]|\\s{0,2}[°º˚])\\s{0,2}[NSEOW]\\b");
    /** Grados y minutos: 5°31'55"N, 5º 31.9' , 73° 22′. */
    private static final Pattern GRADOS_MINUTOS = Pattern.compile(
            "\\d{1,3}\\s{0,2}[°º˚]\\s{0,2}\\d{1,2}(?:[.,]\\d+)?\\s{0,2}['′’´]");
    /** Palabra clave de coordenada seguida de un número: "lat: 5.5", "lng=-73". ("longitud" también significa largo). */
    private static final Pattern CLAVE_Y_NUMERO = Pattern.compile(
            "\\b(?:lat|latitud|latitude|lng|lon|long|longitude)\\b\\.?\\s{0,2}[:=]?\\s{0,2}" + NEG + "?\\d",
            Pattern.CASE_INSENSITIVE);
    /**
     * Hemisferio DELANTE (Garmin y otros): "N5.53214 W73.36712", "latitud: N 5.53214", "N05 31.928 W073 22.027",
     * "N05°31.928". Un solo valor exige 4 decimales o grados con minutos; con 3 decimales hace falta el par.
     */
    private static final Pattern HEMISFERIO_DELANTE = Pattern.compile(
            "\\b[NSEOW]\\s{0,2}\\d{1,3}(?:[.,]\\d{4,}|\\s{0,2}[°º˚]\\s{0,2}\\d|\\s{1,2}\\d{1,2}[.,]\\d{1,4})"
                    + "|\\b[NS]\\s{0,2}\\d{1,2}[.,]\\d{3}" + SEP + "[EOW]\\s{0,2}\\d{1,3}[.,]\\d{3}\\b");
    /** Grados-minutos-segundos sin símbolos y hemisferio detrás: "5 31 55 N", "5°31N". */
    private static final Pattern GMS_SIN_SIMBOLOS = Pattern.compile(
            "\\b\\d{1,3}(?:\\s{0,2}[°º˚]\\s{0,2}|\\s{1,2})\\d{1,2}(?:\\s{1,2}\\d{1,2}(?:[.,]\\d+)?)?\\s{0,2}[NSEOW]\\b");
    /** Grados y minutos en palabras: "5 grados 31 minutos norte". */
    private static final Pattern GRADOS_EN_PALABRAS = Pattern.compile(
            "(?iu)\\b\\d{1,3}\\s{0,2}grados?\\s{0,2}\\d{1,2}\\s{0,2}(?:minutos?|['′’´])");
    /** Par sin signo dentro de Colombia (latitud 0-13, longitud 66-82): "5.532, 73.367". No confunde "3.200, 3.500 msnm". */
    private static final Pattern PAR_COLOMBIA = Pattern.compile(
            "\\b(?:\\d|1[0-3])[.,]\\d{3,}" + SEP + "0?(?:6[6-9]|7\\d|8[0-2])[.,]\\d{3,}\\b");
    /** Hemisferio en palabras: "5.53214 norte", "latitud norte 5.53214", "norte 5.53214". */
    private static final Pattern PALABRA_HEMISFERIO = Pattern.compile(
            "(?iu)\\b(?:\\d{1,3}[.,]\\d{3,}\\s{0,2}(?:norte|sur|este|oeste)\\b"
                    + "|(?:latitud|longitud)\\s{0,2}(?:norte|sur|este|oeste)\\s{0,2}:?\\s{0,2}\\d"
                    + "|(?:norte|sur|este|oeste)\\s{0,2}:?\\s{0,2}\\d{1,3}[.,]\\d{4,})");
    /** Coordenadas planas MAGNA/IGAC (7 cifras): "Norte 1012345 Este 1045678", "N: 1.012.345". */
    private static final Pattern PLANAS = Pattern.compile(
            "(?iu)\\b(?:norte|este|n|e)\\s{0,2}[:=]?\\s{0,2}\\d(?:\\.?\\d{3}){2}\\b");
    /** UTM con zona y banda: "18N 612345 1012345". */
    private static final Pattern UTM = Pattern.compile(
            "\\b\\d{1,2}[C-HJ-NP-X]\\s{1,3}\\d{5,7}" + SEP + "\\d{5,8}\\b");

    /**
     * Hemisferio en minúscula (SEC-I27g-1): "5 31 55 n", "5°31 n", "n05 31.928 w073 22.027". Detrás solo n/w (la "o" y la
     * "e" son conjunciones y la "s" choca con los segundos); delante n/s/w con la misma exigencia que en mayúscula.
     */
    private static final Pattern HEMISFERIO_MINUSCULA = Pattern.compile(
            "\\b\\d{1,3}(?:\\s{0,2}[°º˚]\\s{0,2}|\\s{1,2})\\d{1,2}(?:\\s{1,2}\\d{1,2}(?:[.,]\\d+)?)?\\s{0,2}[nw]\\b"
                    + "|\\b[nsw]\\s{0,2}\\d{1,3}(?:[.,]\\d{4,}|\\s{0,2}[°º˚]\\s{0,2}\\d|\\s{1,2}\\d{1,2}[.,]\\d{1,4})");
    /** GMS sin "°" pero con minutos y segundos ("5 31' 55\""), o separado con ":" o "-" y con hemisferio ("05:31:55 N"). */
    private static final Pattern GMS_OTROS = Pattern.compile(
            "\\b\\d{1,3}\\s{1,2}\\d{1,2}(?:[.,]\\d+)?\\s{0,2}['′’´]\\s{0,2}\\d{1,2}"
                    + "|\\b\\d{1,3}[:\\-]\\d{2}[:\\-]\\d{2}(?:[.,]\\d+)?\\s{0,2}[NSEOWnw]\\b");
    /** Par de Colombia con la longitud delante (orden de GeoJSON, WKT y QGIS): "POINT(-73.367 5.532)". */
    private static final Pattern PAR_COLOMBIA_LON_LAT = Pattern.compile(
            "[-+]?\\b0?(?:6[6-9]|7\\d|8[0-2])[.,]\\d{3,}" + SEP + "[-+]?(?:\\d|1[0-3])[.,]\\d{3,}\\b");
    /** Par unido por "y" con 4 o más decimales y la longitud con signo: "5.53214 y -73.36712" (no marca "CV 2.345 y -1.234"). */
    private static final Pattern PAR_CON_Y = Pattern.compile(
            "[-+]?\\d{1,3}[.,]\\d{4,}\\s{1,3}y\\s{1,3}[-+]\\d{1,3}[.,]\\d{4,}");

    private DetectorCoordenadas() {
    }

    /**
     * Normaliza el texto ANTES de buscar, para que no se evada con caracteres equivalentes: NFKC (dígitos y signos de
     * ancho completo), cualquier dígito Unicode a ASCII, espacios duros y de otros anchos a espacio, caracteres de
     * ancho cero eliminados y guiones Unicode a "-", y las rachas de espacios o saltos de línea colapsadas a un espacio (SEC-I27g-1). El grado volado "º"/"˚" se pasa a "°" antes de NFKC, que lo
     * convertiría en la letra "o".
     */
    public static String normalizar(String texto) {
        String t = texto.replace('º', '°').replace('˚', '°');
        t = Normalizer.normalize(t, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 128) {
                sb.append(c);
            } else if (Character.isDigit(c)) {
                sb.append((char) ('0' + Character.digit(c, 10)));
            } else if (esEspacioRaro(c)) {
                sb.append(' ');
            } else if (c == 0x00AD || (c >= 0x200B && c <= 0x200F) || c == 0x2060 || c == 0xFEFF) {
                continue; // ancho cero: se elimina para no partir un número
            } else if ((c >= 0x2010 && c <= 0x2015) || c == 0x2212 || c == 0xFE63 || c == 0xFF0D) {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        // cualquier racha de espacios, tabuladores o saltos de línea (con sangría) pasa a un solo espacio: así SEP basta
        return sb.toString().replaceAll("\\s+", " ");
    }

    /** Espacios que {@code \s} no cubre: duro, de otros anchos, ideográfico, separadores de línea/párrafo. */
    private static boolean esEspacioRaro(char c) {
        return c == 0x00A0 || c == 0x0085 || c == 0x1680 || c == 0x2028 || c == 0x2029 || c == 0x202F
                || c == 0x205F || c == 0x3000 || (c >= 0x2000 && c <= 0x200A);
    }

    /** {@code true} si el texto contiene algo con forma de coordenada (decimal, con hemisferio, grados-minutos, plana o por palabra clave). */
    public static boolean pareceCoordenada(String texto) {
        if (texto == null || texto.isEmpty()) {
            return false;
        }
        String t = normalizar(texto);
        return DECIMAL_CON_SIGNO.matcher(t).find()
                || DECIMAL_SIGNO_SEPARADO.matcher(t).find()
                || DECIMAL_PRECISO.matcher(t).find()
                || DECIMAL_HEMISFERIO.matcher(t).find()
                || GRADOS_MINUTOS.matcher(t).find()
                || CLAVE_Y_NUMERO.matcher(t).find()
                || HEMISFERIO_DELANTE.matcher(t).find()
                || GMS_SIN_SIMBOLOS.matcher(t).find()
                || GRADOS_EN_PALABRAS.matcher(t).find()
                || PAR_COLOMBIA.matcher(t).find()
                || PALABRA_HEMISFERIO.matcher(t).find()
                || PLANAS.matcher(t).find()
                || UTM.matcher(t).find()
                || HEMISFERIO_MINUSCULA.matcher(t).find()
                || GMS_OTROS.matcher(t).find()
                || PAR_COLOMBIA_LON_LAT.matcher(t).find()
                || PAR_CON_Y.matcher(t).find();
    }

    /** {@code true} si la clave es de coordenadas (sin distinguir mayúsculas). */
    public static boolean esClaveProhibida(Object clave) {
        return clave != null && CLAVES_PROHIBIDAS.contains(clave.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Recorre mapas, listas y cadenas en cualquier profundidad: {@code true} si alguna CLAVE es de coordenadas o
     * algún VALOR de texto parece una coordenada.
     */
    public static boolean contiene(Object nodo) {
        return contiene(nodo, 0);
    }

    private static boolean contiene(Object nodo, int profundidad) {
        if (profundidad > 64) { // una estructura tan honda no es un evento legítimo: se trata como sospechosa
            return true;
        }
        if (nodo instanceof Map<?, ?> mapa) {
            for (Map.Entry<?, ?> e : mapa.entrySet()) {
                if (esClaveProhibida(e.getKey()) || (e.getKey() instanceof String k && pareceCoordenada(k))
                        || contiene(e.getValue(), profundidad + 1)) {
                    return true;
                }
            }
        } else if (nodo instanceof Collection<?> coleccion) {
            for (Object o : coleccion) {
                if (contiene(o, profundidad + 1)) {
                    return true;
                }
            }
        } else if (nodo instanceof String s) {
            return pareceCoordenada(s);
        }
        return false;
    }
    // </reglas-compartidas>

    /** Máximo de bytes del cuerpo que se analiza en bruto (el broker permite mensajes mucho mayores). */
    static final int MAXIMO_BYTES_ANALIZADOS = 1024 * 1024;

    /** Claves prohibidas escritas como clave JSON en un cuerpo que no se pudo interpretar. */
    private static final Pattern CLAVE_JSON_PROHIBIDA = Pattern.compile(
            "[\"']\\s{0,3}(?:latitud|longitud|lat|lon|lng|latitude|longitude|coordenadas|coordinates|geometry|geom"
                    + "|ubicacionexacta)\\s{0,3}[\"']\\s{0,3}:", Pattern.CASE_INSENSITIVE);

    /**
     * Análisis de un cuerpo que NO se pudo interpretar como evento (JSON roto, bytes inválidos, demasiado grande):
     * busca claves de coordenadas y texto con forma de coordenada en el cuerpo en bruto, tras deshacer los escapes
     * JSON básicos. Evita que ese mensaje, con coordenadas, acabe retenido en la DLQ.
     */
    public static boolean contieneEnBruto(byte[] cuerpo) {
        if (cuerpo == null || cuerpo.length == 0) {
            return false;
        }
        int largo = Math.min(cuerpo.length, MAXIMO_BYTES_ANALIZADOS);
        String texto = desescapar(new String(cuerpo, 0, largo, StandardCharsets.UTF_8));
        return CLAVE_JSON_PROHIBIDA.matcher(texto).find() || pareceCoordenada(texto);
    }

    /** Deshace {@code \"}, {@code \\} y {@code \}{@code uXXXX} para que los patrones vean el texto real. */
    static String desescapar(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'u' && esHex(s, i + 2)) {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                }
                if (n == '"' || n == '\\' || n == '/') {
                    sb.append(n);
                    i++;
                    continue;
                }
                if (n == 'n' || n == 'r' || n == 't') {
                    sb.append(' ');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean esHex(String s, int desde) {
        if (desde + 4 > s.length()) {
            return false;
        }
        for (int i = desde; i < desde + 4; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
