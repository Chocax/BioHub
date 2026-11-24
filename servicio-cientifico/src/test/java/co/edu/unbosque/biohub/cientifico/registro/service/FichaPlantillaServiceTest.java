package co.edu.unbosque.biohub.cientifico.registro.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import co.edu.unbosque.biohub.cientifico.registro.domain.AtributoPlantilla;
import co.edu.unbosque.biohub.cientifico.registro.domain.FichaPlantilla;
import co.edu.unbosque.biohub.cientifico.registro.domain.FichaPlantillaRepository;
import co.edu.unbosque.biohub.cientifico.registro.domain.TipoAtributo;

/** HU-02: agregar campos sin tocar código, sin romper lo ya guardado y validando lo que se captura. */
class FichaPlantillaServiceTest {

    private static final String ESPECIE = "Atelopus ebenoides";

    private FichaPlantillaRepository repositorio;
    private FichaPlantillaService servicio;

    @BeforeEach
    void prepararServicio() {
        repositorio = Mockito.mock(FichaPlantillaRepository.class);
        when(repositorio.save(any(FichaPlantilla.class))).thenAnswer(i -> i.getArgument(0));
        servicio = new FichaPlantillaService(repositorio, new ValidadorTextoPublico());
    }

    private static AtributoPlantilla temperatura() {
        return new AtributoPlantilla("temperatura_agua", "Temperatura del agua", TipoAtributo.NUMERO,
                false, true, "°C");
    }

    @Test
    @DisplayName("la primera plantilla de una especie es la version 1")
    void primeraPlantilla() {
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.empty());

        FichaPlantilla guardada = servicio.guardarVersion(ESPECIE, List.of(temperatura()), "cientifico1");

        assertThat(guardada.getVersion()).isEqualTo(1);
        assertThat(guardada.getAtributos()).hasSize(1);
    }

    @Test
    @DisplayName("cambiar la plantilla crea una version nueva, no modifica la anterior (criterio 3)")
    void cadaCambioCreaVersionNueva() {
        FichaPlantilla anterior = new FichaPlantilla(ESPECIE, 3, List.of(temperatura()), "cientifico1");
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.of(anterior));

        FichaPlantilla nueva = servicio.guardarVersion(ESPECIE,
                List.of(temperatura(), new AtributoPlantilla("canto", "Canto", TipoAtributo.TEXTO, false, true, null)),
                "cientifico1");

        assertThat(nueva.getVersion()).isEqualTo(4);
        assertThat(anterior.getVersion()).isEqualTo(3);
        assertThat(anterior.getAtributos()).hasSize(1);
    }

    @Test
    @DisplayName("un atributo deshabilitado deja de pedirse pero no se borra")
    void atributoDeshabilitado() {
        AtributoPlantilla retirado = new AtributoPlantilla("canto", "Canto", TipoAtributo.TEXTO, false, false, null);
        FichaPlantilla plantilla = new FichaPlantilla(ESPECIE, 1, List.of(temperatura(), retirado), "cientifico1");

        assertThat(plantilla.getAtributos()).hasSize(2);
        assertThat(plantilla.getAtributosHabilitados()).extracting(AtributoPlantilla::clave)
                .containsExactly("temperatura_agua");
    }

    @Test
    @DisplayName("una clave repetida o con formato invalido se rechaza")
    void rechazaClavesInvalidas() {
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.guardarVersion(ESPECIE, List.of(temperatura(), temperatura()), "c1"))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("repetida");

        AtributoPlantilla claveMala = new AtributoPlantilla("Temperatura Agua", "x", TipoAtributo.TEXTO,
                false, true, null);
        assertThatThrownBy(() -> servicio.guardarVersion(ESPECIE, List.of(claveMala), "c1"))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("clave invalida");
    }

    @Test
    @DisplayName("se valida el tipo de cada valor capturado (criterio 2)")
    void validaElTipoDeLosValores() {
        FichaPlantilla plantilla = new FichaPlantilla(ESPECIE, 1, List.of(temperatura()), "cientifico1");
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.of(plantilla));

        assertThatCode(() -> servicio.validarValores(ESPECIE, Map.of("temperatura_agua", 12.5)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> servicio.validarValores(ESPECIE, Map.of("temperatura_agua", "frio")))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("numero");
    }

    @Test
    @DisplayName("un atributo obligatorio que falta se rechaza; uno desconocido tambien")
    void exigeObligatoriosYRechazaDesconocidos() {
        AtributoPlantilla obligatorio = new AtributoPlantilla("adn", "Secuencia ADN", TipoAtributo.TEXTO,
                true, true, null);
        FichaPlantilla plantilla = new FichaPlantilla(ESPECIE, 1, List.of(obligatorio), "cientifico1");
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.of(plantilla));

        assertThatThrownBy(() -> servicio.validarValores(ESPECIE, Map.of()))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("obligatorio");

        assertThatThrownBy(() -> servicio.validarValores(ESPECIE, Map.of("adn", "ACGT", "inventado", "x")))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("no definido");
    }

    @Test
    @DisplayName("sin plantilla configurada no se exige nada, pero tampoco se aceptan atributos sueltos")
    void especieSinPlantilla() {
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.empty());

        assertThatCode(() -> servicio.validarValores(ESPECIE, Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> servicio.validarValores(ESPECIE, Map.of("x", 1)))
                .isInstanceOf(DatosHallazgoInvalidosException.class);
    }

    @Test
    @DisplayName("SEC-I27-2: una plantilla no puede definir atributos con nombre de coordenada")
    void rechazaClavesDeCoordenadasEnLaPlantilla() {
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.empty());
        for (String clave : List.of("latitud_gps", "lat", "lon", "lng", "gps", "utm_este", "coordenadas",
                "georef", "ubicacion_exacta")) {
            assertThatThrownBy(() -> servicio.guardarVersion(ESPECIE,
                    List.of(new AtributoPlantilla(clave, "Etiqueta", TipoAtributo.NUMERO, false, true, null)), "cientifico1"))
                    .as(clave).isInstanceOf(DatosHallazgoInvalidosException.class);
        }
        // "longitud", "x" e "y" solo si el atributo es numérico
        for (String clave : List.of("longitud", "x", "y")) {
            assertThatThrownBy(() -> servicio.guardarVersion(ESPECIE,
                    List.of(new AtributoPlantilla(clave, "Etiqueta", TipoAtributo.ENTERO, false, true, null)), "cientifico1"))
                    .as(clave).isInstanceOf(DatosHallazgoInvalidosException.class);
            assertThatCode(() -> servicio.guardarVersion(ESPECIE,
                    List.of(new AtributoPlantilla(clave, "Etiqueta", TipoAtributo.TEXTO, false, true, null)), "cientifico1"))
                    .as(clave + " como texto").doesNotThrowAnyException();
        }
        for (String clave : List.of("longitud_cm", "longitud_total", "longitud_hocico_cloaca")) {
            assertThatCode(() -> servicio.guardarVersion(ESPECIE,
                    List.of(new AtributoPlantilla(clave, "Longitud", TipoAtributo.NUMERO, false, true, "cm")), "cientifico1"))
                    .as(clave).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("SEC-I27g-2: norte/este/northing/easting no pueden ser atributos numéricos de una plantilla")
    void rechazaClavesPlanasNumericasEnLaPlantilla() {
        when(repositorio.findFirstByNombreCientificoOrderByVersionDesc(ESPECIE)).thenReturn(Optional.empty());
        for (String clave : List.of("norte", "este", "northing", "easting", "norte_m", "este1")) {
            for (TipoAtributo tipo : List.of(TipoAtributo.NUMERO, TipoAtributo.ENTERO)) {
                assertThatThrownBy(() -> servicio.guardarVersion(ESPECIE,
                        List.of(new AtributoPlantilla(clave, "Etiqueta", tipo, false, true, null)), "cientifico1"))
                        .as(clave + " " + tipo).isInstanceOf(DatosHallazgoInvalidosException.class);
            }
            assertThatCode(() -> servicio.guardarVersion(ESPECIE,
                    List.of(new AtributoPlantilla(clave, "Etiqueta", TipoAtributo.TEXTO, false, true, null)), "cientifico1"))
                    .as(clave + " como texto").doesNotThrowAnyException();
        }
        // palabras parecidas que no son coordenadas siguen permitidas
        for (String clave : List.of("nortenio", "estero", "estado", "estadio")) {
            assertThatCode(() -> servicio.guardarVersion(ESPECIE,
                    List.of(new AtributoPlantilla(clave, "Etiqueta", TipoAtributo.NUMERO, false, true, null)), "cientifico1"))
                    .as(clave).doesNotThrowAnyException();
        }
    }
}
