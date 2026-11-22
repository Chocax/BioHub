package co.edu.unbosque.biohub.consultapublica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.unbosque.biohub.consultapublica.config.ConfiguracionDegradacion;
import co.edu.unbosque.biohub.consultapublica.config.SecurityConfig;
import co.edu.unbosque.biohub.consultapublica.service.CatalogoDepartamentos;
import co.edu.unbosque.biohub.consultapublica.service.ConsultaEspeciesService;
import co.edu.unbosque.biohub.consultapublica.service.ElectorEstrategiaBusqueda;
import co.edu.unbosque.biohub.consultapublica.service.FotosEspeciesService;
import co.edu.unbosque.biohub.consultapublica.service.MonitorCarga;
import co.edu.unbosque.biohub.consultapublica.service.ValidadorBusqueda;
import co.edu.unbosque.biohub.consultapublica.service.vista.FamiliasResponse;
import co.edu.unbosque.biohub.consultapublica.service.vista.PaginaEspeciesResponse;

/**
 * ARQ-R-5: el monitor mide la portada (catálogo, familias y fotos) y, con la carga alta, el catálogo y las familias
 * se degradan. Cadena real filtro -> monitor -> elector -> controlador, sin base de datos.
 */
@WebMvcTest(EspeciesPublicasController.class)
@Import({SecurityConfig.class, ValidadorBusqueda.class, ElectorEstrategiaBusqueda.class, MonitorCarga.class,
        ConfiguracionDegradacion.class})
@TestPropertySource(properties = {
        "biohub.publica.degradacion.en-curso-entrada=3", "biohub.publica.degradacion.en-curso-salida=1",
        "biohub.publica.degradacion.permanencia-minima=0s", "biohub.publica.degradacion.intervalo-evaluacion=0s"})
class DegradacionCatalogoWebTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MonitorCarga monitor;
    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private ConsultaEspeciesService especies;
    @MockitoBean
    private FotosEspeciesService fotos;
    @MockitoBean
    private CatalogoDepartamentos catalogo;

    @Test
    @DisplayName("ARQ-R-5: cada lectura de /especies (catálogo, familias, fotos) cuenta como muestra y no deja peticiones en curso")
    void elMonitorMideLaPortada() throws Exception {
        given(especies.buscar(any())).willReturn(new PaginaEspeciesResponse(List.of(), 0, 20, 0, 0, false, List.of()));
        given(especies.familias(false)).willReturn(new FamiliasResponse(List.of("Bufonidae")));
        given(fotos.sha256(any())).willReturn(Optional.empty());
        int antes = monitor.estado().muestras(); // el contexto se comparte con la otra prueba de la clase

        mvc.perform(get("/api/publica/especies")).andExpect(status().isOk());
        mvc.perform(get("/api/publica/especies/familias")).andExpect(status().isOk());
        mvc.perform(get("/api/publica/especies/fotos/3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b")).andExpect(status().isNotFound());

        assertThat(monitor.estado().muestras() - antes).isEqualTo(3);
        assertThat(monitor.estado().enCurso()).isZero();
    }

    @Test
    @DisplayName("ARQ-R-5: con la carga alta, el catálogo se pide degradado y las familias van vacías sin consultar la base")
    void conCargaAltaSeDegrada() throws Exception {
        given(especies.buscar(any())).willAnswer(i -> {
            var c = (co.edu.unbosque.biohub.consultapublica.service.CriterioBusquedaPublica) i.getArgument(0);
            return new PaginaEspeciesResponse(List.of(), c.page(), c.size(), 0, 0, c.modoDegradado(), c.filtrosIgnorados());
        });
        given(especies.familias(true)).willReturn(new FamiliasResponse(List.of()));

        for (int i = 0; i < 3; i++) {
            monitor.iniciarPeticion();
        }
        try {
            mvc.perform(get("/api/publica/especies?familia=Bufonidae"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modoDegradado").value(true))
                    .andExpect(jsonPath("$.filtrosIgnorados[0]").value("familia"));
            mvc.perform(get("/api/publica/especies/familias"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.familias").isEmpty());
            verify(especies).familias(true);
        } finally {
            for (int i = 0; i < 3; i++) {
                monitor.terminarPeticion(5); // el monitor se comparte con la otra prueba de la clase
            }
        }
    }
}
