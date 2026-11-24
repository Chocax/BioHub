package co.edu.unbosque.biohub.cientifico.registro.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.unbosque.biohub.cientifico.config.SecurityConfig;
import co.edu.unbosque.biohub.cientifico.ServicioCientificoApplication;
import co.edu.unbosque.biohub.cientifico.registro.domain.HallazgoRepository;
import co.edu.unbosque.biohub.cientifico.registro.service.FichaPlantillaService;
import co.edu.unbosque.biohub.cientifico.registro.service.HallazgoService;
import co.edu.unbosque.biohub.cientifico.registro.service.HistorialService;
import co.edu.unbosque.biohub.cientifico.registro.service.RepublicadorEventosHallazgoService;

@WebMvcTest({HallazgoController.class, HistorialController.class, FichaPlantillaController.class})
@Import({SecurityConfig.class, RegistroExceptionHandler.class})
@ContextConfiguration(classes = ServicioCientificoApplication.class)
class RegistroRolesApiTest {

    private static final UUID HALLAZGO_ID = UUID.fromString("c0a9a000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private HallazgoService hallazgoService;
    @MockitoBean
    private HallazgoRepository hallazgos;
    @MockitoBean
    private RepublicadorEventosHallazgoService republicador;
    @MockitoBean
    private HistorialService historial;
    @MockitoBean
    private FichaPlantillaService plantillas;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor rol(String rol) {
        return jwt().jwt(token -> token.claim("preferred_username", "usuario-prueba"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @Test
    void investigadorPuedeRegistrarPeroAuditorNo() throws Exception {
        given(hallazgoService.registrar(any(), anyString(), eq("asr2-1")))
                .willReturn(new co.edu.unbosque.biohub.cientifico.registro.service.ResultadoRegistro(HALLAZGO_ID, 1));
        String cuerpo = """
                {"nombreCientifico":"Atelopus ebenoides","familia":"Bufonidae","tipoEspecimen":"Adulto",
                 "fechaRecoleccion":"2026-03-14","departamento":"15"}
                """;

        mvc.perform(post("/api/cientifico/hallazgos").with(rol("investigador_campo"))
                        .header("Idempotency-Key", "asr2-1").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/cientifico/hallazgos").with(rol("auditor_cientifico"))
                        .header("Idempotency-Key", "asr2-2").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());
        verify(hallazgoService).registrar(any(), anyString(), eq("asr2-1"));
        verify(hallazgoService, never()).registrar(any(), anyString(), eq("asr2-2"));
    }

    @Test
    void soloRolesCientificosPuedenLeerHistorial() throws Exception {
        given(historial.versionesDe(HALLAZGO_ID)).willReturn(List.of());

        mvc.perform(get("/api/cientifico/hallazgos/{id}/versiones", HALLAZGO_ID)
                        .with(rol("investigador_campo")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/cientifico/hallazgos/{id}/versiones", HALLAZGO_ID)
                        .with(rol("auditor_cientifico")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void investigadorYAltoRangoConfiguranFichaPeroAuditorNo() throws Exception {
        given(plantillas.exigirVigente("Atelopus-ebenoides"))
                .willReturn(new co.edu.unbosque.biohub.cientifico.registro.domain.FichaPlantilla(
                        "Atelopus-ebenoides", 1, List.of(), "cientifico1"));
        given(plantillas.guardarVersion(eq("Atelopus-ebenoides"), any(), eq("usuario-prueba")))
                .willReturn(new co.edu.unbosque.biohub.cientifico.registro.domain.FichaPlantilla(
                        "Atelopus-ebenoides", 2, List.of(), "usuario-prueba"));
        String url = "/api/cientifico/fichas/Atelopus-ebenoides/atributos";

        mvc.perform(get(url).with(rol("investigador_campo"))).andExpect(status().isOk());
        mvc.perform(put(url).with(rol("investigador_campo")).contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
        mvc.perform(put(url).with(rol("cientifico_alto_rango")).contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
        mvc.perform(put(url).with(rol("auditor_cientifico")).contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
        verify(plantillas, times(2)).guardarVersion(eq("Atelopus-ebenoides"), any(), eq("usuario-prueba"));
    }

    @Test
    void jsonInvalidoUsaElFormatoDeErrorEstandar() throws Exception {
        mvc.perform(post("/api/cientifico/hallazgos").with(rol("investigador_campo"))
                        .header("Idempotency-Key", "json-roto").contentType(MediaType.APPLICATION_JSON)
                        .content("{roto"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("JSON_INVALIDO"))
                .andExpect(jsonPath("$.errores[0].campo").value("cuerpo"));
        verify(hallazgoService, never()).registrar(any(), anyString(), anyString());
    }
}
