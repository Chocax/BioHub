package co.edu.unbosque.biohub.cientifico.registro;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.cientifico.registro.domain.CategoriaUicn;
import co.edu.unbosque.biohub.cientifico.registro.service.HallazgoService;
import co.edu.unbosque.biohub.cientifico.registro.service.RegistrarHallazgoComando;

/**
 * D65: galería de especies y listas para autocompletar (la ruta de foto pública se eliminó en D66; ver
 * FotosPublicasEventosMySqlTest), con MySQL real y la cadena de seguridad
 * real. Requiere Docker.
 */
@AutoConfigureMockMvc
class EspeciesYFotoPublicaMySqlTest extends BaseRegistroMySqlTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private HallazgoService hallazgos;
    @Autowired
    private ObjectMapper json;

    private UUID ranaBoyaca;

    private UUID registrar(String especie, String comun, String familia, String departamento, CategoriaUicn uicn,
                           LocalDate fecha) {
        return hallazgos.registrar(new RegistrarHallazgoComando(especie, comun, familia, "Adulto", fecha, departamento,
                uicn, "Registro de prueba", 5.5, -73.3, null), "investigador1").hallazgoId();
    }

    @BeforeEach
    void datos() {
        ranaBoyaca = registrar("Atelopus ebenoides", "Rana arlequin", "Bufonidae", "Boyaca", CategoriaUicn.EN, LocalDate.of(2026, 3, 1));
        registrar("Atelopus ebenoides", "Rana arlequin de Boyaca", "Bufonidae", "Cundinamarca", CategoriaUicn.EN, LocalDate.of(2026, 4, 1));
        registrar("Ara macao", "Guacamaya", "Psittacidae", "Meta", CategoriaUicn.LC, LocalDate.of(2026, 2, 1));
    }

    private static RequestPostProcessor como(String rol) {
        return jwt().jwt(j -> j.claim("preferred_username", "investigador1"))
                .authorities(Stream.of(rol).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    /** Un JPEG de verdad (ImageIO lo puede leer), de 2000 x 1500. */
    private static byte[] jpeg() throws Exception {
        BufferedImage img = new BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(60, 120, 40));
        g.fillRect(0, 0, 2000, 1500);
        g.dispose();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", salida);
        return salida.toByteArray();
    }

    private String subirFoto(UUID hallazgo) throws Exception {
        String cuerpo = mvc.perform(multipart("/api/cientifico/hallazgos/" + hallazgo + "/evidencias")
                        .file(new MockMultipartFile("archivo", "rana.jpg", "image/jpeg", jpeg()))
                        .with(como("investigador_campo")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(cuerpo).get("id").asText();
    }

    private String rutaPublica(UUID hallazgo, String evidencia) {
        return "/api/cientifico/hallazgos/" + hallazgo + "/evidencias/" + evidencia + "/publica";
    }

    @Test
    void laGaleriaAgrupaPorEspecieEIncluyeLasQueSoloTienenFicha() throws Exception {
        mvc.perform(put("/api/cientifico/fichas/{n}/atributos", "Oophaga histrionica").with(como("cientifico_alto_rango"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"clave\":\"canto\",\"etiqueta\":\"Canto\",\"tipo\":\"BOOLEANO\",\"obligatorio\":false,\"habilitado\":true}]"))
                .andExpect(status().is2xxSuccessful());
        String evidencia = subirFoto(ranaBoyaca);

        mvc.perform(get("/api/cientifico/especies").with(como("auditor_cientifico")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.content[0].nombreCientifico").value("Ara macao"))
                .andExpect(jsonPath("$.content[1].nombreCientifico").value("Atelopus ebenoides"))
                .andExpect(jsonPath("$.content[1].registros").value(2))
                .andExpect(jsonPath("$.content[1].departamentos.length()").value(2))
                // los datos de la tarjeta son los del registro más reciente
                .andExpect(jsonPath("$.content[1].nombreComun").value("Rana arlequin de Boyaca"))
                .andExpect(jsonPath("$.content[1].foto.evidenciaId").value(evidencia))
                .andExpect(jsonPath("$.content[1].foto.publica").value(false))
                .andExpect(jsonPath("$.content[2].nombreCientifico").value("Oophaga histrionica"))
                .andExpect(jsonPath("$.content[2].registros").value(0))
                .andExpect(jsonPath("$.content[2].fichaVersion").value(1))
                .andExpect(jsonPath("$.content[2].atributosFicha").value(1));
    }

    @Test
    void filtraPorTextoYFamiliaYPagina() throws Exception {
        mvc.perform(get("/api/cientifico/especies?q=guacamaya").with(como("investigador_campo")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].nombreCientifico").value("Ara macao"));
        mvc.perform(get("/api/cientifico/especies?familia=Bufonidae").with(como("investigador_campo")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].nombreCientifico").value("Atelopus ebenoides"));
        mvc.perform(get("/api/cientifico/especies?size=1&page=1").with(como("investigador_campo")))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/cientifico/especies?size=500").with(como("investigador_campo")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lasSugerenciasSonParaTodosLosRolesYLaGaleriaNoParaElAnalista() throws Exception {
        mvc.perform(get("/api/cientifico/especies/sugerencias").with(como("analista_ambiental")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.especies.length()").value(2))
                .andExpect(jsonPath("$.familias[0]").value("Bufonidae"))
                .andExpect(jsonPath("$.investigadores[0]").value("investigador1"));
        mvc.perform(get("/api/cientifico/especies").with(como("analista_ambiental")))
                .andExpect(status().isForbidden());
    }

    @Test
    void soloQuienRegistraDecideSiLaFotoEsPublica() throws Exception {
        String evidencia = subirFoto(ranaBoyaca);
        for (String rol : new String[] {"auditor_cientifico", "analista_ambiental", "administrador"}) {
            mvc.perform(put(rutaPublica(ranaBoyaca, evidencia)).with(como(rol))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"publica\":true}"))
                    .andExpect(status().isForbidden());
        }
    }
}
