package co.edu.unbosque.biohub.reportes.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.unbosque.biohub.reportes.config.SecurityConfig;
import co.edu.unbosque.biohub.reportes.service.ConsultasReportesService;
import co.edu.unbosque.biohub.reportes.service.ConsultasReportesService.ConteoCategoria;
import co.edu.unbosque.biohub.reportes.service.ConsultasReportesService.ResumenAnalitico;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

@WebMvcTest(controllers = ReportesController.class)
@Import({SecurityConfig.class, ReportesExceptionHandler.class, ReportesControllerTest.TestBeansConfig.class})
class ReportesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConsultasReportesService service;

    @Test
    void sinTokenResponde401() throws Exception {
        mockMvc.perform(get("/api/reportes/resumen"))
            .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"investigador_campo", "auditor_cientifico", "cientifico_alto_rango"})
    void rolesNoAutorizadosReciben403(String rol) throws Exception {
        mockMvc.perform(get("/api/reportes/resumen")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-" + rol))
            .andExpect(status().isForbidden());
    }

    @Test
    void analistaAmbientalConsultaResumenYExportaCsvYPdf() throws Exception {
        when(service.generarResumen(any(), any(), any(), any(), any()))
            .thenReturn(new ResumenAnalitico(
                Instant.now(), 12, 4, 3,
                List.of(new ConteoCategoria("Boyacá", 7)),
                List.of(new ConteoCategoria("Bufonidae", 5)),
                List.of(new ConteoCategoria("EN_PELIGRO", 3))
            ));
        when(service.exportarCsv(any(), any(), any(), any(), any()))
            .thenReturn("hallazgo_id,version\n".getBytes(StandardCharsets.UTF_8));
        when(service.exportarPdf(any(), any(), any(), any(), any()))
            .thenReturn("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));

        mockMvc.perform(get("/api/reportes/resumen")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-analista_ambiental"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalHallazgos").value(12))
            .andExpect(jsonPath("$.hallazgosEnPeligro").value(3));

        mockMvc.perform(get("/api/reportes/exportar/csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-analista_ambiental"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reporte-biohub.csv\""));

        mockMvc.perform(get("/api/reportes/exportar/pdf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-analista_ambiental"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @TestConfiguration
    static class TestBeansConfig {
        @Bean
        ConsultasReportesService consultasReportesService() {
            return mock(ConsultasReportesService.class);
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
