package co.edu.unbosque.biohub.cientifico.registro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.cientifico.registro.domain.AlmacenEvidencias;
import co.edu.unbosque.biohub.cientifico.registro.domain.CategoriaUicn;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgoRepository;
import co.edu.unbosque.biohub.cientifico.registro.service.CambioHallazgoConfirmado;
import co.edu.unbosque.biohub.cientifico.registro.service.DatosHallazgoInvalidosException;
import co.edu.unbosque.biohub.cientifico.registro.service.EvidenciaService;
import co.edu.unbosque.biohub.cientifico.registro.service.HallazgoService;
import co.edu.unbosque.biohub.cientifico.registro.service.RegistrarHallazgoComando;
import co.edu.unbosque.biohub.cientifico.registro.service.RepublicadorFotosPublicas;

/**
 * D66, lado productor: miniatura generada una sola vez, eventos {@code evidencia.*} solo después del commit,
 * republicación de administrador, migración de fotos públicas anteriores y ruta de D65 eliminada. MySQL real
 * (Testcontainers) y cadena de seguridad real; el broker se sustituye por un registro de los eventos que
 * recibiría el listener AFTER_COMMIT. Requiere Docker.
 */
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(FotosPublicasEventosMySqlTest.Configuracion.class)
class FotosPublicasEventosMySqlTest extends BaseRegistroMySqlTest {

    /** Recibe, como lo haría {@code PublicadorCambiosHallazgo}, los eventos ya confirmados. */
    static class RegistroEventos {
        final List<CambioHallazgoConfirmado> confirmados = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void despuesDelCommit(CambioHallazgoConfirmado cambio) {
            confirmados.add(cambio);
        }
    }

    @TestConfiguration
    static class Configuracion {
        @Bean
        RegistroEventos registroEventos() {
            return new RegistroEventos();
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private HallazgoService hallazgos;
    @Autowired
    private EvidenciaService evidencias;
    @Autowired
    private EvidenciaHallazgoRepository repositorio;
    @Autowired
    private AlmacenEvidencias almacen;
    @Autowired
    private RepublicadorFotosPublicas republicador;
    @Autowired
    private RegistroEventos registro;
    @Autowired
    private ObjectMapper json;

    private UUID hallazgo;

    @BeforeEach
    void preparar() {
        hallazgo = hallazgos.registrar(new RegistrarHallazgoComando("Atelopus ebenoides", "Rana arlequin",
                "Bufonidae", "Adulto", LocalDate.of(2026, 3, 1), "Boyaca", CategoriaUicn.EN, "Registro de prueba",
                5.5, -73.3, null), "investigador1").hallazgoId();
        registro.confirmados.clear();
    }

    private static RequestPostProcessor como(String rol) {
        return jwt().jwt(j -> j.claim("preferred_username", "investigador1"))
                .authorities(Stream.of(rol).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private static byte[] jpeg(int ancho, int alto) throws Exception {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(60, 120, 40));
        g.fillRect(0, 0, ancho, alto);
        g.dispose();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", salida);
        return salida.toByteArray();
    }

    private UUID subirFoto() throws Exception {
        String cuerpo = mvc.perform(multipart("/api/cientifico/hallazgos/" + hallazgo + "/evidencias")
                        .file(new MockMultipartFile("archivo", "rana.jpg", "image/jpeg", jpeg(2000, 1500)))
                        .with(como("investigador_campo")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(cuerpo).get("id").asText());
    }

    private void marcar(UUID evidencia, boolean publica) throws Exception {
        mvc.perform(put("/api/cientifico/hallazgos/" + hallazgo + "/evidencias/" + evidencia + "/publica")
                        .with(como("investigador_campo")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publica\":" + publica + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publica").value(publica));
    }

    @Test
    @DisplayName("al marcarla pública se genera la miniatura y sale evidencia.publicada con el contrato 7.6")
    void marcarPublicaGeneraMiniaturaYPublica() throws Exception {
        UUID evidencia = subirFoto();
        assertThat(registro.confirmados).isEmpty();

        marcar(evidencia, true);

        assertThat(registro.confirmados).hasSize(1);
        CambioHallazgoConfirmado evento = registro.confirmados.get(0);
        assertThat(evento.tipo()).isEqualTo("evidencia.publicada");
        assertThat(evento.usuario()).isEqualTo("investigador1");
        Map<String, Object> datos = evento.datos();
        assertThat(datos).containsOnlyKeys("evidenciaId", "hallazgoId", "cambioEn", "sha256", "ancho", "alto", "jpegBase64");
        assertThat(datos.get("evidenciaId")).isEqualTo(evidencia.toString());
        assertThat(datos.get("hallazgoId")).isEqualTo(hallazgo.toString());
        assertThat(Instant.parse((String) datos.get("cambioEn"))).isBefore(Instant.now().plusSeconds(1));

        byte[] miniatura = Base64.getDecoder().decode((String) datos.get("jpegBase64"));
        assertThat(miniatura.length).isLessThanOrEqualTo(600_000);
        assertThat(datos.get("sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(miniatura)));
        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(miniatura));
        assertThat(leida.getWidth()).isEqualTo(1200).isEqualTo(datos.get("ancho"));
        assertThat(leida.getHeight()).isEqualTo(900).isEqualTo(datos.get("alto"));

        // Se guardó en disco junto al original y su ruta quedó en la columna nueva
        String ruta = jdbc.queryForObject("SELECT ruta_miniatura FROM hallazgo_evidencia WHERE id = UUID_TO_BIN(?)",
                String.class, evidencia.toString());
        assertThat(ruta).isNotNull();
        assertThat(Files.readAllBytes(CARPETA_EVIDENCIAS.resolve(ruta))).isEqualTo(miniatura);
        assertThat(jdbc.queryForObject("SELECT publica_cambio_en IS NOT NULL FROM hallazgo_evidencia WHERE id = UUID_TO_BIN(?)",
                Boolean.class, evidencia.toString())).isTrue();
    }

    @Test
    @DisplayName("la miniatura se genera una sola vez: desmarcar y volver a marcar reutiliza el archivo")
    void laMiniaturaSeGeneraUnaSolaVez() throws Exception {
        UUID evidencia = subirFoto();
        marcar(evidencia, true);
        String ruta = jdbc.queryForObject("SELECT ruta_miniatura FROM hallazgo_evidencia WHERE id = UUID_TO_BIN(?)",
                String.class, evidencia.toString());
        Path archivo = CARPETA_EVIDENCIAS.resolve(ruta);
        var antes = Files.getLastModifiedTime(archivo);
        byte[] bytesAntes = Files.readAllBytes(archivo);

        marcar(evidencia, false);
        Thread.sleep(20);
        marcar(evidencia, true);

        assertThat(Files.getLastModifiedTime(archivo)).isEqualTo(antes);
        assertThat(Files.readAllBytes(archivo)).isEqualTo(bytesAntes);
        assertThat(registro.confirmados).extracting(CambioHallazgoConfirmado::tipo)
                .containsExactly("evidencia.publicada", "evidencia.retirada", "evidencia.publicada");
        // cambioEn crece con cada cambio de visibilidad (ordena los eventos en el consumidor)
        List<Instant> instantes = registro.confirmados.stream().map(c -> Instant.parse((String) c.datos().get("cambioEn"))).toList();
        assertThat(instantes.get(1)).isAfter(instantes.get(0));
        assertThat(instantes.get(2)).isAfter(instantes.get(1));
    }

    @Test
    @DisplayName("al desmarcarla sale evidencia.retirada (sin bytes), y repetir el mismo estado no publica nada")
    void desmarcarPublicaRetirada() throws Exception {
        UUID evidencia = subirFoto();
        marcar(evidencia, true);
        registro.confirmados.clear();

        marcar(evidencia, true); // sin cambio
        assertThat(registro.confirmados).isEmpty();

        marcar(evidencia, false);
        assertThat(registro.confirmados).hasSize(1);
        CambioHallazgoConfirmado retirada = registro.confirmados.get(0);
        assertThat(retirada.tipo()).isEqualTo("evidencia.retirada");
        assertThat(retirada.datos()).containsOnlyKeys("evidenciaId", "hallazgoId", "cambioEn");

        marcar(evidencia, false); // sin cambio
        assertThat(registro.confirmados).hasSize(1);
    }

    @Test
    @DisplayName("el evento sale DESPUÉS del commit y no antes: dentro de la transacción aún no existe")
    void elEventoSaleTrasElCommit() throws Exception {
        UUID evidencia = subirFoto();

        tx.executeWithoutResult(estado -> {
            evidencias.marcarPublica(hallazgo, evidencia, true, "investigador1");
            assertThat(registro.confirmados).as("antes del commit").isEmpty();
        });

        assertThat(registro.confirmados).as("después del commit").hasSize(1);
    }

    @Test
    @DisplayName("si la transacción se revierte, ninguna foto queda pública y no sale ningún evento")
    void sinCommitNoHayEvento() throws Exception {
        UUID evidencia = subirFoto();

        tx.executeWithoutResult(estado -> {
            evidencias.marcarPublica(hallazgo, evidencia, true, "investigador1");
            estado.setRollbackOnly();
        });

        assertThat(registro.confirmados).isEmpty();
        assertThat(repositorio.findById(evidencia).orElseThrow().isPublica()).isFalse();
    }

    @Test
    @DisplayName("una imagen de más de 40 MP se rechaza con 400, sin evento y sin dejarla pública")
    void rechazaMasDeCuarentaMegapixeles() throws Exception {
        UUID evidencia = UUID.randomUUID();
        byte[] enorme = pngQueDeclara(10_000, 5_000);
        String ruta = almacen.guardar(hallazgo, evidencia, enorme);
        repositorio.saveAndFlush(new EvidenciaHallazgo(evidencia, hallazgo, "enorme.png",
                EvidenciaHallazgo.TipoEvidencia.FOTOGRAFIA, "image/png", enorme.length, ruta, "b".repeat(64), "investigador1"));

        mvc.perform(put("/api/cientifico/hallazgos/" + hallazgo + "/evidencias/" + evidencia + "/publica")
                        .with(como("investigador_campo")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publica\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));

        assertThat(registro.confirmados).isEmpty();
        assertThat(repositorio.findById(evidencia).orElseThrow().isPublica()).isFalse();
        assertThat(repositorio.findById(evidencia).orElseThrow().getRutaMiniatura()).isNull();
    }

    @Test
    @DisplayName("un audio no se puede hacer público (400) y no sale evento")
    void unAudioNoEsPublico() throws Exception {
        UUID evidencia = UUID.randomUUID();
        byte[] wav = "RIFF....WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
        String ruta = almacen.guardar(hallazgo, evidencia, wav);
        repositorio.saveAndFlush(new EvidenciaHallazgo(evidencia, hallazgo, "canto.wav",
                EvidenciaHallazgo.TipoEvidencia.AUDIO, "audio/wav", wav.length, ruta, "c".repeat(64), "investigador1"));

        mvc.perform(put("/api/cientifico/hallazgos/" + hallazgo + "/evidencias/" + evidencia + "/publica")
                        .with(como("investigador_campo")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publica\":true}"))
                .andExpect(status().isBadRequest());
        assertThat(registro.confirmados).isEmpty();
    }

    @Test
    @DisplayName("republicar: solo administrador (los otros 4 roles dan 403, sin token 401); responde 202 con el número de fotos")
    void republicarSoloAdministrador() throws Exception {
        UUID evidencia = subirFoto();
        marcar(evidencia, true);
        registro.confirmados.clear();

        for (String rol : new String[] {"investigador_campo", "cientifico_alto_rango", "auditor_cientifico", "analista_ambiental"}) {
            mvc.perform(post("/api/cientifico/admin/fotos-publicas/republicar").with(como(rol)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/cientifico/admin/fotos-publicas/republicar")).andExpect(status().isUnauthorized());
        assertThat(registro.confirmados).isEmpty();

        mvc.perform(post("/api/cientifico/admin/fotos-publicas/republicar").with(como("administrador")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fotos").value(1));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(registro.confirmados).hasSize(1));
        CambioHallazgoConfirmado evento = registro.confirmados.get(0);
        assertThat(evento.tipo()).isEqualTo("evidencia.publicada");
        assertThat(evento.datos().get("evidenciaId")).isEqualTo(evidencia.toString());
        assertThat(evento.datos()).containsKey("jpegBase64");
    }

    @Test
    @DisplayName("republicar sin fotos públicas responde 202 con 0 y no publica nada")
    void republicarSinFotos() throws Exception {
        subirFoto(); // existe pero no es pública
        mvc.perform(post("/api/cientifico/admin/fotos-publicas/republicar").with(como("administrador")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fotos").value(0));
        assertThat(registro.confirmados).isEmpty();
    }

    @Test
    @DisplayName("migración al arrancar: una foto ya pública sin miniatura la recibe y se publica; una con miniatura no se repite")
    void migraLasFotosPublicasAnterioresADosSesentaYSeis() throws Exception {
        UUID evidencia = subirFoto();
        // Estado previo a D66: marcada como pública directamente, sin miniatura ni instante de cambio
        jdbc.update("UPDATE hallazgo_evidencia SET publica = TRUE WHERE id = UUID_TO_BIN(?)", evidencia.toString());
        assertThat(repositorio.findById(evidencia).orElseThrow().getRutaMiniatura()).isNull();

        assertThat(republicador.republicarSinMiniatura()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(registro.confirmados).hasSize(1));
        EvidenciaHallazgo migrada = repositorio.findById(evidencia).orElseThrow();
        assertThat(migrada.getRutaMiniatura()).isNotNull();
        assertThat(migrada.getPublicaCambioEn()).isNotNull();
        assertThat(registro.confirmados.get(0).tipo()).isEqualTo("evidencia.publicada");

        // ya tiene miniatura: el arranque siguiente no la republica
        assertThat(republicador.republicarSinMiniatura()).isZero();
    }

    @Test
    @DisplayName("una foto pública cuya imagen no se puede leer no detiene la republicación de las demás")
    void unaFotoRotaNoDetieneLasDemas() throws Exception {
        UUID buena = subirFoto();
        marcar(buena, true);
        UUID rota = UUID.randomUUID();
        byte[] basura = "no es una imagen".getBytes(StandardCharsets.UTF_8);
        String ruta = almacen.guardar(hallazgo, rota, basura);
        repositorio.saveAndFlush(new EvidenciaHallazgo(rota, hallazgo, "rota.jpg",
                EvidenciaHallazgo.TipoEvidencia.FOTOGRAFIA, "image/jpeg", basura.length, ruta, "d".repeat(64), "investigador1"));
        jdbc.update("UPDATE hallazgo_evidencia SET publica = TRUE WHERE id = UUID_TO_BIN(?)", rota.toString());
        registro.confirmados.clear();

        assertThat(republicador.republicarTodas()).isEqualTo(2);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(registro.confirmados).hasSize(1));
        assertThat(registro.confirmados.get(0).datos().get("evidenciaId")).isEqualTo(buena.toString());
    }

    @Test
    @DisplayName("D66: la ruta sin rol de D65 ya no existe (sin token 401; con token 404)")
    void laRutaDeD65SeElimino() throws Exception {
        mvc.perform(get("/api/cientifico/publico/especies/foto").param("nombre", "Atelopus ebenoides"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cientifico/publico/especies/foto").param("nombre", "Atelopus ebenoides")
                        .with(como("administrador")))
                .andExpect(status().isNotFound());
    }

    // ---- BE-R-1 / SEC-R-2: un solo detector, 400 antes del commit ----

    @Test
    @DisplayName("BE-R-1 / SEC-R-2: texto o atributo con coordenadas da 400 y no deja nada guardado ni evento")
    void elDetectorUnificadoFallaAntesDelCommit() {
        long antes = jdbc.queryForObject("SELECT COUNT(*) FROM hallazgo", Long.class);
        for (String texto : new String[] {"colectado en 5.53214, -73.36712, ejemplar de 12 cm",
                "colectado en 5,53214 -73,36712", "lat 5.53214 lon -73.36712", "4.6097, -74.0817 (a 100 m del camino)"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> hallazgos.registrar(new RegistrarHallazgoComando(
                    "Ara macao", null, "Psittacidae", "Adulto", LocalDate.of(2026, 3, 1), "Meta", CategoriaUicn.LC,
                    texto, null, null, null), "investigador1"))
                    .as(texto).isInstanceOf(DatosHallazgoInvalidosException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> hallazgos.registrar(new RegistrarHallazgoComando(
                "Ara macao", null, "Psittacidae", "Adulto", LocalDate.of(2026, 3, 1), "Meta", CategoriaUicn.LC,
                "Guacamaya", null, null, Map.of("sitio", "cerca de 5.53214, -73.36712")), "investigador1"))
                .isInstanceOf(DatosHallazgoInvalidosException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hallazgo", Long.class)).isEqualTo(antes);
        assertThat(registro.confirmados).isEmpty();
    }

    private static byte[] pngQueDeclara(int ancho, int alto) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(ancho).putInt(alto).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(s, "IHDR", ihdr.array());
        chunk(s, "IEND", new byte[0]);
        return s.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream s, String tipo, byte[] datos) {
        byte[] t = tipo.getBytes(StandardCharsets.US_ASCII);
        s.writeBytes(ByteBuffer.allocate(4).putInt(datos.length).array());
        s.writeBytes(t);
        s.writeBytes(datos);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(datos);
        s.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
