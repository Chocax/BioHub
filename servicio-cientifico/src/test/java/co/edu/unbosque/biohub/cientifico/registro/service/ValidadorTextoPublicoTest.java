package co.edu.unbosque.biohub.cientifico.registro.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SEC-J-02: de nada sirve cifrar las coordenadas si alguien las escribe en la descripción. Pero una
 * regla demasiado estricta bloquearía texto científico legítimo, así que ambos lados importan.
 */
class ValidadorTextoPublicoTest {

    private final ValidadorTextoPublico validador = new ValidadorTextoPublico();

    @ParameterizedTest
    @ValueSource(strings = {
            "Visto en 5.53214, -73.36712 cerca del rio",
            "5.53214,-73.36712",
            "Coordenada 5°31'55\"N",
            "73° 22' 10\" W",
            "Ubicado a 5.53° N",
            "El punto exacto es 4.6097100, -74.0817500"
    })
    @DisplayName("detecta coordenadas escritas en texto")
    void detectaCoordenadas(String texto) {
        assertThat(validador.contieneCoordenadas(texto)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Recolectado el 2026-03-14 en expedicion",
            "Ejemplar de 12,5 cm de longitud",
            "Habita entre 3.200 y 3.500 msnm",
            "Temperatura del agua: 12.5 °C",
            "Anfibio endemico de paramo, piel negra con manchas",
            "Se observaron 1.250 individuos en la zona",
            "Atelopus ebenoides",
            "Bufonidae"
    })
    @DisplayName("no confunde fechas, medidas ni altitudes con coordenadas")
    void noConfundeTextoLegitimo(String texto) {
        assertThat(validador.contieneCoordenadas(texto)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("un texto vacio o ausente no es un problema")
    void textoVacio(String texto) {
        assertThat(validador.contieneCoordenadas(texto)).isFalse();
    }

    @Test
    @DisplayName("verificar informa el campo que falla, sin repetir el valor")
    void verificarInformaElCampo() {
        assertThatThrownBy(() -> validador.verificar("descripcion", "Visto en 5.53214, -73.36712"))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessage("no puede contener coordenadas")
                .extracting(e -> ((DatosHallazgoInvalidosException) e).campo())
                .isEqualTo("descripcion");
    }

    @Test
    @DisplayName("el mensaje de error nunca incluye la coordenada detectada")
    void elMensajeNoFiltraLaCoordenada() {
        assertThatThrownBy(() -> validador.verificar("descripcion", "Visto en 5.53214, -73.36712"))
                .hasMessageNotContaining("5.53214")
                .hasMessageNotContaining("73.36712");
    }

    /** SEC-R-2 y BE-R-1: textos que el validador anterior dejaba pasar y el publicador rechazaba o la fuga permitía. */
    @ParameterizedTest
    @ValueSource(strings = {
            "colectado en 5.53214, -73.36712, ejemplar de 12 cm",
            "colectado en 5,53214 -73,36712",
            "lat 5.53214 lon -73.36712",
            "4.6097, -74.0817 (a 100 m del camino)",
            "long. 12 cm",
            "lat 3",
            "lat: 5.5",
            "5,5°N 73,3°W",
            "5°31’55”N"
    })
    @DisplayName("SEC-R-2 / BE-R-1: el mismo detector estricto que el publicador")
    void detectaLoQueAntesSeColaba(String texto) {
        assertThat(validador.contieneCoordenadas(texto)).isTrue();
        assertThatThrownBy(() -> validador.verificar("descripcion", texto))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ejemplar adulto de 12 cm, pelaje pardo", "Longitud total: 12 cm", "Largo 12 cm y 3,2 g",
            "A 2.800 msnm, 12.5 °C", "Observado el 2026-03-14 hacia las 06:30", "Cerca del km 12 de la vía",
            "Atelopus ebenoides (Rivero, 1963)"
    })
    @DisplayName("el detector estricto sigue dejando pasar texto científico común")
    void sigueDejandoPasarTextoComun(String texto) {
        assertThat(validador.contieneCoordenadas(texto)).isFalse();
    }

    @Test
    @DisplayName("paridad: el validador de registro y el del publicador dan siempre la misma respuesta")
    void validadorYPublicadorCoinciden() {
        for (String texto : new String[] {"5.53214, -73.36712", "lat 5.5", "12 cm", "long. 12 cm", "5°31'55\"N",
                "3.200, 3.500 msnm", "", "Bufonidae", "lon -73", "5,5°N", "1.250 individuos"}) {
            assertThat(validador.contieneCoordenadas(texto))
                    .as(texto).isEqualTo(co.edu.unbosque.biohub.cientifico.eventos.service.DetectorCoordenadas
                            .pareceCoordenada(texto));
        }
    }

    @Test
    @DisplayName("SEC-R-2: los valores de texto de los atributos también se revisan, a cualquier profundidad")
    void revisaLosAtributos() {
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("nota", "en 5.53214, -73.36712")))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .extracting(e -> ((DatosHallazgoInvalidosException) e).campo()).isEqualTo("atributos.nota");
        assertThatThrownBy(() -> validador.verificarAtributos(
                java.util.Map.of("otros", java.util.Map.of("x", java.util.List.of("ok", "lat 5.5 lon -73.3")))))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        validador.verificarAtributos(java.util.Map.of("peso", 12.5, "adulto", true, "color", "verde", "n", 5));
        validador.verificarAtributos(null);
        validador.verificarAtributos(java.util.Map.of());
    }

    @Test
    @DisplayName("un atributo anidado sin fondo se rechaza en vez de recorrerlo")
    void rechazaAtributosDemasiadoAnidados() {
        Object nodo = "x";
        for (int i = 0; i < 40; i++) {
            nodo = java.util.Map.of("k", nodo);
        }
        Object anidado = nodo;
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("a", anidado)))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    // ---- SEC-I27-2: claves y números en los atributos flexibles ----

    @ParameterizedTest
    @ValueSource(strings = {"lat", "lon", "lng", "Lat", "LON", "latitud", "Latitud_GPS", "latitude", "longitude",
            "coord", "coordenada", "coordenadas", "Coordenadas-UTM", "gps", "GPS_punto", "utm", "utm_este", "georef",
            "georeferencia", "ubicacion_exacta", "ubicación exacta", "ubicacionExacta", "lat_inicio", "lon1",
            "punto_gps", "lat\u00A0"})
    @DisplayName("SEC-I27-2: se rechazan las claves de coordenadas, con cualquier valor")
    void rechazaClavesDeCoordenadas(String clave) {
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of(clave, "algo")))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(
                java.util.Map.of("datos", java.util.List.of(java.util.Map.of(clave, 1)))))
                .as("a cualquier profundidad").isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"longitud_cm", "longitud_total", "longitud_hocico_cloaca", "Longitud total", "lateral",
            "latencia", "talla_cm", "peso_g", "coordinador", "color", "nota", "x_pos", "xy"})
    @DisplayName("SEC-I27-2: las claves biológicas parecidas siguen permitidas")
    void permiteClavesLegitimas(String clave) {
        validador.verificarAtributos(java.util.Map.of(clave, 12.5));
        validador.verificarAtributos(java.util.Map.of(clave, "12 cm"));
    }

    @Test
    @DisplayName("SEC-I27-2: longitud, long, x e y solo se rechazan si el valor es numérico (número o texto)")
    void longitudXYSoloSiNumericos() {
        for (String clave : new String[] {"longitud", "Longitud", "long", "x", "y"}) {
            assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of(clave, 12)))
                    .as(clave).isInstanceOf(DatosHallazgoInvalidosException.class);
            assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of(clave, "12,5")))
                    .as(clave + " texto numérico").isInstanceOf(DatosHallazgoInvalidosException.class);
            validador.verificarAtributos(java.util.Map.of(clave, "alargado"));
        }
    }

    @Test
    @DisplayName("SEC-I27-2: un par numérico con forma de latitud/longitud se rechaza, como número, texto o lista")
    void rechazaParesNumericos() {
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("punto", java.util.List.of(5.53214, -73.36712))))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("a", 5.532, "b", -73.367)))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("a", "5.53214", "b", "-73,36712")))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("a", 5.53214, "b", "-73.36712")))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("sitio", java.util.Map.of("p", 40.7128, "q", 74.006))))
                .as("anidado, fuera de Colombia").isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("a", "\uFF15.\uFF15\uFF13\uFF12\uFF11\uFF14", "b", 73.36712)))
                .as("ancho completo").isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    @Test
    @DisplayName("SEC-I27-2: números sueltos o con pocos decimales, o fuera de rango, se aceptan")
    void permiteNumerosLegitimos() {
        validador.verificarAtributos(java.util.Map.of("peso", 12.5, "talla", 3.25, "n", 5));
        validador.verificarAtributos(java.util.Map.of("temperatura", 12.345));                    // uno solo
        validador.verificarAtributos(java.util.Map.of("altitud", 2600.123, "profundidad", 300.456)); // fuera de [-90, 90]
        validador.verificarAtributos(java.util.Map.of("a", 5.5, "b", -73.3));                       // menos de 3 decimales
        validador.verificarAtributos(java.util.Map.of("codigo", "A-123", "fecha", "2026-03-14"));
        validador.verificarAtributos(java.util.Map.of("medidas", java.util.List.of(12, 13, 14)));
    }

    @Test
    @DisplayName("SEC-I27-2: el error no devuelve una clave que parece una coordenada")
    void noEcoaClavesConCoordenadas() {
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("5.53214, -73.36712", "x")))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .extracting(e -> ((DatosHallazgoInvalidosException) e).campo()).isEqualTo("atributos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"latInicio", "lonInicio", "geoLat", "geoLon", "latE7", "lngE7", "punto.lat", "punto.lon",
            "punto.Lng", "gpsPunto"})
    @DisplayName("SEC-I27g-2: se separan camelCase y punto antes de revisar la clave")
    void rechazaClavesCamelCaseYConPunto(String clave) {
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of(clave, 5.53)))
                .as(clave).isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of(clave, "algo")))
                .as(clave + " con texto").isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"norte", "Norte", "este", "ESTE", "northing", "Northing", "easting", "norte_m", "este1",
            "esteMagna", "northingM"})
    @DisplayName("SEC-I27g-2: norte/este/northing/easting con valor numérico (número o texto) se rechazan")
    void rechazaClavesPlanasConValorNumerico(String clave) {
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of(clave, 1012345)))
                .as(clave).isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of(clave, "1012345")))
                .as(clave + " texto").isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("datos", java.util.List.of(java.util.Map.of(clave, 1045678)))))
                .as(clave + " anidado").isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarClavePlantilla("atributos." + clave.toLowerCase(), clave, true))
                .as(clave + " plantilla").isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    @Test
    @DisplayName("SEC-I27g-2: el par norte/este como enteros se rechaza y no choca con palabras parecidas")
    void planasYPalabrasParecidas() {
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("norte", 1012345, "este", 1045678)))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        assertThatThrownBy(() -> validador.verificarAtributos(java.util.Map.of("northing", "1012345", "easting", "1045678")))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
        // sin valor numérico no son coordenadas ("norte": "ladera")
        validador.verificarAtributos(java.util.Map.of("norte", "ladera", "este", "quebrada"));
        for (String clave : new String[] {"nortenio", "estero", "estado", "estadio", "nortena", "esteril", "latencia"}) {
            validador.verificarAtributos(java.util.Map.of(clave, 12));
            validador.verificarAtributos(java.util.Map.of(clave, "12"));
        }
        validador.verificarClavePlantilla("atributos.estado", "estado", true);
        validador.verificarClavePlantilla("atributos.norte", "norte", false);
    }
}
