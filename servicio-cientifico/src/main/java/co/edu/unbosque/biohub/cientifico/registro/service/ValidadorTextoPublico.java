package co.edu.unbosque.biohub.cientifico.registro.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import co.edu.unbosque.biohub.cientifico.eventos.service.DetectorCoordenadas;

/**
 * Impide que una coordenada escrita a mano se cuele en un campo de texto que viaja al portal público o se guarda
 * sin cifrar (04-contrato-api §7.3, SEC-J-02, RNF9, D14).
 *
 * <p>El riesgo es real: de nada sirve cifrar las coordenadas si alguien escribe "visto en 5.53214,
 * -73.36712" en la descripción. consulta-publica descarta el evento completo si detecta algo así, de modo que
 * registro debe validarlo <b>antes</b> del commit.
 *
 * <p><b>Un solo detector (BE-R-1, SEC-R-2):</b> este validador no tiene reglas propias; delega en
 * {@link DetectorCoordenadas}, el mismo que aplica {@code PublicadorEventos} al publicar. Así lo que el publicador
 * rechazaría ya falla aquí con 400 y no hay hallazgos guardados sin evento. Registro no importa nada de
 * {@code ubicacion} (D53); {@code eventos.service} es un módulo independiente de ambos.
 */
@Component
public class ValidadorTextoPublico {

    private static final int PROFUNDIDAD_MAXIMA = 16;

    private static final String MENSAJE_CLAVE =
            "nombre reservado a coordenadas; las coordenadas van en latitud/longitud del hallazgo, cifradas";

    /** Claves que empiezan por estos términos (la clave ya está normalizada). */
    private static final Pattern PREFIJOS_PROHIBIDOS = Pattern.compile(
            "^(?:latitud|longitude|coord(?!inador)|gps|utm|georef|ubicacion_?exacta)");
    /** lat/lon/lng solos o como primer término ("lat", "lat_inicio", "lon1"); no "lateral" ni "longitud_cm". */
    private static final Pattern TERMINOS_PROHIBIDOS = Pattern.compile("^(?:lat|lon|lng)(?:_|\\d|$)"
            + "|_(?:gps|utm|georef|lat|lon|lng)(?:_|$)");
    /**
     * Coordenadas planas (SEC-I27g-2): {@code norte}, {@code este}, {@code northing}, {@code easting}, solos o como primer
     * término ({@code norte_m}, {@code este1}). No choca con {@code nortenio}, {@code estero} ni {@code estado}.
     */
    private static final Pattern PLANAS_CLAVE = Pattern.compile("^(?:norte|este|northing|easting)(?:_|\\d|$)");
    private static final Pattern NUMERO_EN_TEXTO = Pattern.compile("[-+]?\\d{1,20}(?:\\.\\d{1,20})?");

    /**
     * @return {@code true} si el texto parece contener una coordenada geográfica
     */
    public boolean contieneCoordenadas(String texto) {
        return texto != null && !texto.isBlank() && DetectorCoordenadas.pareceCoordenada(texto);
    }

    /**
     * Verifica un campo de texto que acabará en el portal público.
     *
     * @throws DatosHallazgoInvalidosException si el texto parece contener coordenadas
     */
    public void verificar(String campo, String texto) {
        if (contieneCoordenadas(texto)) {
            throw new DatosHallazgoInvalidosException(campo, "no puede contener coordenadas");
        }
    }

    /**
     * Verifica los atributos flexibles (SEC-R-2, SEC-I27-2), a cualquier profundidad:
     * <ul>
     *   <li>los valores de texto no pueden parecer una coordenada;</li>
     *   <li>los NOMBRES de clave no pueden ser de coordenadas ({@link #esClaveDeCoordenada}), con la excepción de las
     *       variantes de longitud con medida ({@code longitud_cm}, {@code longitud_total});</li>
     *   <li>en un mismo objeto o lista no puede haber un par de valores numéricos (número o texto numérico) que
     *       parezcan latitud y longitud: ambos con 3 o más decimales, uno en [-90, 90] y otro en [-180, 180].</li>
     * </ul>
     * Falso positivo conocido: dos medidas con 3 o más decimales y valores pequeños en el mismo objeto
     * ({@code {"largo": 1.234, "ancho": 2.345}}) se rechazan; se piden con menos decimales o en objetos separados.
     *
     * @throws DatosHallazgoInvalidosException con campo {@code atributos.<clave>} (o {@code atributos} si la propia
     *         clave parece una coordenada, para no devolverla en la respuesta)
     */
    public void verificarAtributos(Map<String, Object> atributos) {
        if (atributos == null) {
            return;
        }
        verificarContenedor("atributos", atributos.values(), 0);
        for (Map.Entry<String, Object> e : atributos.entrySet()) {
            String campo = campoDe("atributos", e.getKey());
            verificarClave(campo, e.getKey(), e.getValue());
            verificarValor(campo, e.getValue(), 0);
        }
    }

    /**
     * Verifica el nombre de un atributo de plantilla (SEC-I27-2). Las claves {@code longitud}, {@code long}, {@code x}
     * e {@code y}, y {@code norte}/{@code este}/{@code northing}/{@code easting}, solo se rechazan si el atributo es
     * numérico.
     *
     * @throws DatosHallazgoInvalidosException si la clave es de coordenadas
     */
    public void verificarClavePlantilla(String campo, String clave, boolean numerico) {
        if (esClaveDeCoordenada(clave, numerico)) {
            throw new DatosHallazgoInvalidosException(campo, MENSAJE_CLAVE);
        }
    }

    private void verificarClave(String campo, String clave, Object valor) {
        verificarClavePlantilla(campo, clave, esNumerico(valor));
    }

    private static String campoDe(String base, Object clave) {
        String k = String.valueOf(clave);
        // si la clave misma parece una coordenada no se devuelve en el error
        return DetectorCoordenadas.pareceCoordenada(k) ? base : base + "." + k;
    }

    private void verificarValor(String campo, Object valor, int profundidad) {
        if (valor instanceof String texto) {
            verificar(campo, texto);
        } else if (profundidad >= PROFUNDIDAD_MAXIMA) {
            // Un atributo tan anidado no es legítimo y no se puede revisar entero: se rechaza.
            throw new DatosHallazgoInvalidosException(campo, "demasiado anidado");
        } else if (valor instanceof Map<?, ?> mapa) {
            verificarContenedor(campo, mapa.values(), profundidad + 1);
            for (Map.Entry<?, ?> e : mapa.entrySet()) {
                verificarClave(campo, String.valueOf(e.getKey()), e.getValue());
                verificarValor(campo, e.getValue(), profundidad + 1);
            }
        } else if (valor instanceof Collection<?> coleccion) {
            verificarContenedor(campo, coleccion, profundidad + 1);
            for (Object o : coleccion) {
                verificarValor(campo, o, profundidad + 1);
            }
        }
    }

    /** Un par de valores numéricos del mismo objeto o lista que parezca latitud/longitud. */
    private void verificarContenedor(String campo, Collection<?> valores, int profundidad) {
        if (profundidad > PROFUNDIDAD_MAXIMA) {
            return; // verificarValor lo rechazará por demasiado anidado
        }
        List<BigDecimal> precisos = new ArrayList<>();
        for (Object v : valores) {
            BigDecimal n = comoDecimal(v);
            if (n != null && n.scale() >= 3) {
                precisos.add(n);
            }
            if (precisos.size() > 64) {
                break; // tope para no hacer un recorrido cuadrático con una lista enorme
            }
        }
        for (int i = 0; i < precisos.size(); i++) {
            for (int j = 0; j < precisos.size(); j++) {
                if (i != j && enRango(precisos.get(i), 90) && enRango(precisos.get(j), 180)) {
                    throw new DatosHallazgoInvalidosException(campo,
                            "un par de numeros parece una coordenada; van en latitud/longitud del hallazgo, cifradas");
                }
            }
        }
    }

    private static boolean enRango(BigDecimal n, int limite) {
        return n.abs().compareTo(BigDecimal.valueOf(limite)) <= 0;
    }

    /** Número o texto numérico ("5.53214", "-73,36712"); {@code null} si no lo es. */
    private static BigDecimal comoDecimal(Object valor) {
        try {
            if (valor instanceof Number n) {
                return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
            }
            if (valor instanceof String s) {
                String t = DetectorCoordenadas.normalizar(s).strip().replace(',', '.');
                if (!t.isEmpty() && t.length() <= 40 && NUMERO_EN_TEXTO.matcher(t).matches()) {
                    return new BigDecimal(t);
                }
            }
        } catch (NumberFormatException e) {
            // NaN, infinito o formato raro: no es una coordenada
        }
        return null;
    }

    private static boolean esNumerico(Object valor) {
        return comoDecimal(valor) != null;
    }

    /**
     * Nombre de clave de coordenadas, normalizado (minúsculas, sin tildes, {@code _}/{@code -}/espacio como separador):
     * {@code lat}, {@code lon}, {@code lng} (solos o como primer término: {@code lat_inicio}, {@code lon1}),
     * cualquier clave que empiece por {@code latitud}, {@code longitude}, {@code coord} ({@code coordenada(s)}),
     * {@code gps}, {@code utm}, {@code georef} o {@code ubicacion_exacta}, y la clave exacta {@code longitud},
     * {@code long}, {@code x} o {@code y} cuando el valor es numérico. Se permiten {@code longitud_cm},
     * {@code longitud_total}, {@code longitud_hocico_cloaca}.
     */
    static boolean esClaveDeCoordenada(String clave, boolean valorNumerico) {
        if (clave == null) {
            return false;
        }
        // camelCase y "." se separan ANTES de pasar a minúsculas: latInicio, geoLat, latE7, punto.lat (SEC-I27g-2)
        String n = Normalizer.normalize(clave.replaceAll("([a-z0-9])([A-Z])", "$1_$2"), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip().replaceAll("[_\\-\\s.]+", "_");
        if (DetectorCoordenadas.pareceCoordenada(clave)) {
            return true;
        }
        if (PREFIJOS_PROHIBIDOS.matcher(n).find() || TERMINOS_PROHIBIDOS.matcher(n).find()) {
            return true;
        }
        return valorNumerico && (n.equals("longitud") || n.equals("long") || n.equals("x") || n.equals("y")
                || PLANAS_CLAVE.matcher(n).find());
    }
}
