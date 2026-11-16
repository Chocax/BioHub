package co.edu.unbosque.biohub.cientifico.registro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.cientifico.registro.domain.CategoriaUicn;
import co.edu.unbosque.biohub.cientifico.registro.service.HallazgoService;
import co.edu.unbosque.biohub.cientifico.registro.service.LimpiadorMetadatosTest;
import co.edu.unbosque.biohub.cientifico.registro.service.RegistrarHallazgoComando;

/**
 * RF1 / HU-01 c.2: subir, listar y descargar evidencias con MySQL real, la cadena de seguridad real y archivos en una
 * carpeta temporal. Requiere Docker.
 */
@AutoConfigureMockMvc
class EvidenciasMySqlTest extends BaseRegistroMySqlTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private HallazgoService hallazgos;
    @Autowired
    private ObjectMapper json;

    private UUID hallazgoId;

    @BeforeEach
    void registrarHallazgo() {
        hallazgoId = hallazgos.registrar(new RegistrarHallazgoComando("Atelopus ebenoides", "Rana arlequin",
                "Bufonidae", "Adulto", LocalDate.of(2026, 3, 14), "Boyaca", CategoriaUicn.EN, "Anfibio",
                5.53214, -73.36712, null), "investigador1").hallazgoId();
    }

    private static RequestPostProcessor como(String rol) {
        return jwt().jwt(j -> j.claim("preferred_username", "investigador1"))
                .authorities(Stream.of(rol).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private String ruta() {
        return "/api/cientifico/hallazgos/" + hallazgoId + "/evidencias";
    }

    private MvcResult subir(byte[] contenido, String nombre, String rol, int estado) throws Exception {
        return mvc.perform(multipart(ruta()).file(new MockMultipartFile("archivo", nombre, "image/jpeg", contenido))
                        .with(como(rol)))
                .andExpect(status().is(estado))
                .andReturn();
    }

    @Test
    void laFotoSeGuardaSinGpsYSeDescargaComoAdjunto() throws Exception {
        MvcResult r = subir(LimpiadorMetadatosTest.jpegConGps(), "IMG_0042.JPG", "investigador_campo", 201);
        JsonNode cuerpo = json.readTree(r.getResponse().getContentAsString());
        assertThat(cuerpo.get("tipo").asText()).isEqualTo("FOTOGRAFIA");
        assertThat(cuerpo.get("nombre").asText()).isEqualTo("IMG_0042.jpeg");
        assertThat(cuerpo.get("subidoPor").asText()).isEqualTo("investigador1");
        assertThat(cuerpo.has("ruta")).isFalse();

        // En disco y en la base no queda nada del GPS (RNF9).
        try (Stream<Path> archivos = Files.walk(CARPETA_EVIDENCIAS.resolve(hallazgoId.toString()))) {
            for (Path p : archivos.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude");
            }
        }

        String id = cuerpo.get("id").asText();
        MvcResult descarga = mvc.perform(get(ruta() + "/" + id).with(como("auditor_cientifico")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn();
        assertThat(new String(descarga.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1))
                .doesNotContain("GPSLatitude");

        mvc.perform(get(ruta()).with(como("auditor_cientifico")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void elMismoArchivoDosVecesNoSeDuplica() throws Exception {
        byte[] foto = LimpiadorMetadatosTest.jpegConGps();
        String primera = json.readTree(subir(foto, "a.jpg", "investigador_campo", 201).getResponse()
                .getContentAsString()).get("id").asText();
        String segunda = json.readTree(subir(foto, "b.jpg", "cientifico_alto_rango", 200).getResponse()
                .getContentAsString()).get("id").asText();

        assertThat(segunda).isEqualTo(primera);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hallazgo_evidencia", Integer.class)).isEqualTo(1);
    }

    @Test
    void unFormatoNoAdmitidoSeRechazaSinGuardarNada() throws Exception {
        subir("<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "foto.jpg",
                "investigador_campo", 400);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hallazgo_evidencia", Integer.class)).isZero();
    }

    @Test
    void sinElCampoArchivoEs400() throws Exception {
        mvc.perform(multipart(ruta()).file(new MockMultipartFile("otro", "x.jpg", "image/jpeg", new byte[] {1}))
                        .with(como("investigador_campo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    void soloQuienRegistraPuedeSubir() throws Exception {
        for (String rol : new String[] {"auditor_cientifico", "analista_ambiental", "administrador"}) {
            subir(LimpiadorMetadatosTest.jpegConGps(), "a.jpg", rol, 403);
        }
        mvc.perform(get(ruta()).with(como("analista_ambiental"))).andExpect(status().isForbidden());
    }

    @Test
    void unHallazgoInexistenteOUnaEvidenciaDeOtroHallazgoEs404() throws Exception {
        mvc.perform(multipart("/api/cientifico/hallazgos/" + UUID.randomUUID() + "/evidencias")
                        .file(new MockMultipartFile("archivo", "a.jpg", "image/jpeg", LimpiadorMetadatosTest.jpegConGps()))
                        .with(como("investigador_campo")))
                .andExpect(status().isNotFound());
        mvc.perform(get(ruta() + "/" + UUID.randomUUID()).with(como("auditor_cientifico")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("EVIDENCIA_NO_ENCONTRADA"));
    }
}
