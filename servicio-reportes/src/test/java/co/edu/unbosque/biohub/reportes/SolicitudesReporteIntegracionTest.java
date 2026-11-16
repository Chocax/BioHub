package co.edu.unbosque.biohub.reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.reportes.domain.OrigenSolicitud;
import co.edu.unbosque.biohub.reportes.domain.SolicitudReporte;
import co.edu.unbosque.biohub.reportes.domain.SolicitudReporteRepository;
import co.edu.unbosque.biohub.reportes.service.SolicitudesReporteService;

/**
 * HU-09 de punta a punta (SD-08), con la aplicación completa, MySQL 8.4 y RabbitMQ reales: el analista pide el
 * reporte (202), la cola lo procesa en segundo plano y luego descarga el CSV y el PDF con los índices.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "biohub.reportes.programacion=-")
@AutoConfigureMockMvc
class SolicitudesReporteIntegracionTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("reportes").withUsername("test").withPassword("test")
            .withCommand("--sql-require-primary-key=ON");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SolicitudReporteRepository solicitudes;
    @Autowired
    private SolicitudesReporteService servicio;

    private static RequestPostProcessor como(String rol) {
        return jwt().jwt(j -> j.claim("preferred_username", "analista1"))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + rol)));
    }

    @BeforeEach
    void sembrar() {
        jdbc.update("DELETE FROM reporte_archivo");
        jdbc.update("DELETE FROM solicitud_reporte");
        jdbc.update("DELETE FROM hallazgo_reporte");
        // Marzo 2026: Boyacá 4 de 5 en peligro (alerta); Huila 0 de 3. Diciembre a febrero: Boyacá 3 en peligro.
        for (int k = 0; k < 5; k++) {
            hallazgo("Boyacá", k < 4 ? "EN_PELIGRO" : "NO_AMENAZADA", LocalDate.of(2026, 3, 1 + k), "Atelopus sp" + k);
        }
        for (int k = 0; k < 3; k++) {
            hallazgo("Huila", "NO_AMENAZADA", LocalDate.of(2026, 3, 10 + k), "Rhinella sp" + k);
            hallazgo("Boyacá", "EN_PELIGRO", LocalDate.of(2026, 1, 5 + k), "Atelopus ant" + k);
        }
        hallazgo("Boyacá", "EN_PELIGRO", LocalDate.of(2026, 4, 2), "Fuera del periodo");
    }

    private void hallazgo(String depto, String estado, LocalDate fecha, String especie) {
        jdbc.update("""
                INSERT INTO hallazgo_reporte (hallazgo_id, version, nombre_cientifico, familia, fecha_recoleccion,
                                              estado_conservacion, departamento)
                VALUES (UUID_TO_BIN(?), 1, ?, 'Bufonidae', ?, ?, ?)""",
                UUID.randomUUID().toString(), especie, fecha, estado, depto);
    }

    private JsonNode esperarLista(String id) throws Exception {
        for (int intento = 0; intento < 60; intento++) {
            String cuerpo = mvc.perform(get("/api/reportes/solicitudes/" + id).with(como("analista_ambiental")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode n = json.readTree(cuerpo);
            if (!"PENDIENTE".equals(n.get("estado").asText()) && !"EN_PROCESO".equals(n.get("estado").asText())) {
                return n;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("El reporte no terminó en 15 s");
    }

    @Test
    void elAnalistaPideElReporteYLoDescargaCuandoEstaListo() throws Exception {
        String cuerpo = mvc.perform(post("/api/reportes/solicitudes").with(como("analista_ambiental"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"periodo\":\"2026-03\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(cuerpo).get("id").asText();

        JsonNode listo = esperarLista(id);
        assertThat(listo.get("estado").asText()).isEqualTo("LISTO");
        JsonNode resumen = listo.get("resumen");
        assertThat(resumen.get("totalHallazgos").asLong()).isEqualTo(8);
        assertThat(resumen.get("zonasEnAlerta")).hasSize(1);
        assertThat(resumen.get("zonasEnAlerta").get(0).asText()).isEqualTo("Boyacá");
        JsonNode boyaca = resumen.get("indices").get(0);
        assertThat(boyaca.get("departamento").asText()).isEqualTo("Boyacá");
        assertThat(boyaca.get("nivel").asText()).isEqualTo("ALTO");

        byte[] csv = mvc.perform(get("/api/reportes/solicitudes/" + id + "/csv").with(como("analista_ambiental")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("2026-03.csv")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("indice_presion").contains("Boyacá").contains("SI");

        byte[] pdf = mvc.perform(get("/api/reportes/solicitudes/" + id + "/pdf").with(como("administrador")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        mvc.perform(get("/api/reportes/solicitudes").with(como("analista_ambiental")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void unReporteSinTerminarNoSeDescargaYProcesarloDosVecesNoLoRepite() throws Exception {
        SolicitudReporte pendiente = solicitudes.save(new SolicitudReporte("2026-02", OrigenSolicitud.MANUAL,
                "analista1", Instant.now()));

        mvc.perform(get("/api/reportes/solicitudes/" + pendiente.getId() + "/csv").with(como("analista_ambiental")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("REPORTE_NO_LISTO"));

        servicio.procesar(pendiente.getId());
        Instant terminado = solicitudes.findById(pendiente.getId()).orElseThrow().getTerminadoEn();
        servicio.procesar(pendiente.getId());
        assertThat(solicitudes.findById(pendiente.getId()).orElseThrow().getTerminadoEn()).isEqualTo(terminado);
    }

    @Test
    void periodoFuturoOMalEscritoEs400() throws Exception {
        String futuro = YearMonth.now().plusMonths(2).toString();
        for (String periodo : new String[] {futuro, "2026-13", "marzo"}) {
            mvc.perform(post("/api/reportes/solicitudes").with(como("analista_ambiental"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"periodo\":\"" + periodo + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void soloAnalistaYAdministrador() throws Exception {
        mvc.perform(post("/api/reportes/solicitudes").with(como("investigador_campo"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"periodo\":\"2026-03\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reportes/solicitudes").with(como("cientifico_alto_rango")))
                .andExpect(status().isForbidden());
    }

    @Test
    void laTareaMensualPideElMesAnteriorUnaSolaVez() {
        assertThat(servicio.solicitarMesAnteriorSiFalta()).isTrue();
        assertThat(servicio.solicitarMesAnteriorSiFalta()).isFalse();
    }
}
