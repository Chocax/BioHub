package co.edu.unbosque.biohub.equipos.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.unbosque.biohub.equipos.config.SecurityConfig;
import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajo;
import co.edu.unbosque.biohub.equipos.domain.EstadoEquipo;
import co.edu.unbosque.biohub.equipos.domain.HistorialCustodiaEquipo;
import co.edu.unbosque.biohub.equipos.service.EquipoTrabajoService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = EquipoTrabajoController.class)
@Import({SecurityConfig.class, EquiposExceptionHandler.class, EquipoTrabajoControllerTest.TestBeansConfig.class})
class EquipoTrabajoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EquipoTrabajoService service;

    @Test
    void sinTokenResponde401() throws Exception {
        mockMvc.perform(get("/api/equipos"))
            .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"investigador_campo", "auditor_cientifico", "cientifico_alto_rango", "analista_ambiental"})
    void rolesDistintosDeAdministradorReciben403(String rol) throws Exception {
        mockMvc.perform(get("/api/equipos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-" + rol))
            .andExpect(status().isForbidden());
    }

    @Test
    void administradorPuedeRegistrarEquipoConCustodioYRecibe201() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.registrar(any(), any(), any(), any(), any(), any()))
            .thenReturn(new EquipoTrabajo(id, "GPS-01", "GPS Garmin", "GEOLOCALIZACION", null,
                "kc-user-1", "investigador1", Instant.now()));

        mockMvc.perform(post("/api/equipos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-administrador")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "codigoInventario": "GPS-01",
                                  "nombre": "GPS Garmin",
                                  "categoria": "GEOLOCALIZACION",
                                  "custodioId": "kc-user-1"
                                }
                                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.codigoInventario").value("GPS-01"))
            .andExpect(jsonPath("$.estado").value("ASIGNADO"))
            .andExpect(jsonPath("$.custodioId").value("kc-user-1"));
    }

    @Test
    void registrarEquipoSinCustodioResponde400() throws Exception {
        mockMvc.perform(post("/api/equipos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-administrador")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "codigoInventario": "GPS-02",
                                  "nombre": "GPS Garmin",
                                  "categoria": "GEOLOCALIZACION"
                                }
                                """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void consultarHistorialDeCustodiaDevuelve200() throws Exception {
        UUID equipoId = UUID.randomUUID();
        when(service.listarHistorial(equipoId)).thenReturn(List.of(
            new HistorialCustodiaEquipo(equipoId, "ALTA_ASIGNADA", null, EstadoEquipo.ASIGNADO,
                "kc-user-1", "investigador1", "Alta inicial", Instant.now())
        ));

        mockMvc.perform(get("/api/equipos/" + equipoId + "/historial")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-administrador"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].accion").value("ALTA_ASIGNADA"))
            .andExpect(jsonPath("$[0].custodioId").value("kc-user-1"));
    }

    @TestConfiguration
    static class TestBeansConfig {
        @Bean
        EquipoTrabajoService equipoTrabajoService() {
            return mock(EquipoTrabajoService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                String rol = token.replace("token-", "");
                return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("user-" + rol)
                    .claim("preferred_username", rol + "_user")
                    .claim("realm_access", Map.of("roles", List.of(rol)))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            };
        }
    }
}
