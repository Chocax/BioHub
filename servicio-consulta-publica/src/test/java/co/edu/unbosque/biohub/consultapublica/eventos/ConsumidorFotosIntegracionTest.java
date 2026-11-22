package co.edu.unbosque.biohub.consultapublica.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.testcontainers.junit.jupiter.Testcontainers;

import co.edu.unbosque.biohub.consultapublica.soporte.BaseIntegracion;
import co.edu.unbosque.biohub.consultapublica.soporte.Eventos;
import co.edu.unbosque.biohub.consultapublica.soporte.Fotos;

/** D66 (04 §7.6): consumidor de {@code evidencia.*} contra RabbitMQ, MySQL y Redis reales. */
@Testcontainers(disabledWithoutDocker = true)
class ConsumidorFotosIntegracionTest extends BaseIntegracion {

    private static final Duration ESPERA = Duration.ofSeconds(15);
    private static final String T1 = "2026-11-21T10:00:00.000001Z";
    private static final String T2 = "2026-11-21T10:00:00.000002Z";
    private static final String T3 = "2026-11-21T10:00:05Z";

    private void publicarFoto(String tipo, Map<String, Object> datos) {
        rabbit.send("biohub.eventos", tipo, Eventos.mensaje(Eventos.sobre(tipo, datos)));
    }

    private Boolean publica(UUID evidencia) {
        List<Boolean> v = jdbc.sql("SELECT publica FROM foto_publica WHERE evidencia_id = UNHEX(REPLACE(?, '-', ''))")
                .param(evidencia.toString()).query(Boolean.class).list();
        return v.isEmpty() ? null : v.get(0);
    }

    private String sha(UUID evidencia) {
        return jdbc.sql("SELECT sha256 FROM foto_publica WHERE evidencia_id = UNHEX(REPLACE(?, '-', ''))")
                .param(evidencia.toString()).query(String.class).single();
    }

    private long filas() {
        return jdbc.sql("SELECT COUNT(*) FROM foto_publica").query(Long.class).single();
    }

    private void esperarPublica(UUID evidencia, Boolean esperado) {
        await().atMost(ESPERA).untilAsserted(() -> assertThat(publica(evidencia)).isEqualTo(esperado));
    }

    /** Publica una foto de control y espera a que se aplique: garantiza que los eventos anteriores se procesaron. */
    private void sincronizar(String semilla) {
        UUID control = id("control-" + semilla);
        byte[] jpeg = Fotos.jpeg(16, 16);
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(control, id("h-control"), T3, jpeg, 16, 16));
        esperarPublica(control, true);
    }

    @Test
    @DisplayName("publicada válida: queda en foto_publica con sus bytes y se sirve")
    void publicadaValida() throws Exception {
        UUID foto = id("foto-valida");
        byte[] jpeg = Fotos.jpeg(80, 60);
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-1"), T1, jpeg, 80, 60));
        esperarPublica(foto, true);

        assertThat(sha(foto)).isEqualTo(Fotos.sha256(jpeg));
        assertThat(jdbc.sql("SELECT ancho FROM foto_publica WHERE evidencia_id = UNHEX(REPLACE(?, '-', ''))")
                .param(foto.toString()).query(Integer.class).single()).isEqualTo(80);
        mvc.perform(MockMvcRequestBuilders.get("/api/publica/especies/fotos/" + foto))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().bytes(jpeg));
    }

    @Test
    @DisplayName("repetida, igual o anterior: se ignora (ack) y no cambia nada")
    void repetidaYDesordenada() {
        UUID foto = id("foto-orden");
        byte[] nueva = Fotos.jpeg(70, 70);
        byte[] vieja = Fotos.jpeg(30, 30);
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-2"), T2, nueva, 70, 70));
        esperarPublica(foto, true);

        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-2"), T2, nueva, 70, 70)); // repetido
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-2"), T2, vieja, 30, 30)); // mismo instante
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-2"), T1, vieja, 30, 30)); // anterior
        publicarFoto("evidencia.retirada", Fotos.datosRetirada(foto, id("hallazgo-2"), T1)); // retirada anterior
        sincronizar("orden");

        assertThat(sha(foto)).isEqualTo(Fotos.sha256(nueva));
        assertThat(publica(foto)).isTrue();
        assertThat(siguienteDeLaDlqDeFotos(Duration.ofMillis(500))).isNull();
    }

    @Test
    @DisplayName("una publicación posterior reemplaza la miniatura")
    void republicadaReemplaza() {
        UUID foto = id("foto-reemplazo");
        byte[] a = Fotos.jpeg(40, 40);
        byte[] b = Fotos.jpeg(41, 41);
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-3"), T1, a, 40, 40));
        esperarPublica(foto, true);
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-3"), T2, b, 41, 41));
        await().atMost(ESPERA).untilAsserted(() -> assertThat(sha(foto)).isEqualTo(Fotos.sha256(b)));
    }

    @Test
    @DisplayName("retirada: publica=false y se borran los bytes; la ruta responde 404")
    void retirada() throws Exception {
        UUID foto = id("foto-retirada");
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-4"), T1, Fotos.jpeg(50, 50), 50, 50));
        esperarPublica(foto, true);

        publicarFoto("evidencia.retirada", Fotos.datosRetirada(foto, id("hallazgo-4"), T2));
        esperarPublica(foto, false);

        assertThat(jdbc.sql("SELECT bytes FROM foto_publica WHERE evidencia_id = UNHEX(REPLACE(?, '-', ''))")
                .param(foto.toString()).query((rs, n) -> rs.getBytes(1)).list()).containsExactly((byte[]) null);
        mvc.perform(MockMvcRequestBuilders.get("/api/publica/especies/fotos/" + foto))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    @DisplayName("retirada que llega antes que la publicación: la publicación anterior se ignora")
    void retiradaAntesQuePublicacion() {
        UUID foto = id("foto-desorden");
        publicarFoto("evidencia.retirada", Fotos.datosRetirada(foto, id("hallazgo-5"), T2));
        esperarPublica(foto, false);
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-5"), T1, Fotos.jpeg(50, 50), 50, 50));
        sincronizar("desorden-retirada");

        assertThat(publica(foto)).isFalse();
        // y una publicación posterior a la retirada sí vuelve a publicarla
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-5"), T3, Fotos.jpeg(50, 50), 50, 50));
        await().atMost(ESPERA).untilAsserted(() -> assertThat(publica(foto)).isTrue());
    }

    @Test
    @DisplayName("JPEG con Exif (APP1): DLQ de fotos y no se guarda nada")
    void jpegConExifVaALaDlq() {
        UUID foto = id("foto-exif");
        byte[] exif = Fotos.conExif(Fotos.jpeg(50, 50));
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-6"), T1, exif, 50, 50));

        Message enDlq = siguienteDeLaDlqDeFotos(Duration.ofSeconds(15));
        assertThat(enDlq).as("la foto con Exif debe ir a consulta-publica.fotos.dlq").isNotNull();
        assertThat(publica(foto)).isNull();
        assertThat(filas()).isZero();
    }

    @Test
    @DisplayName("sha256 que no coincide: DLQ de fotos y no se guarda nada")
    void sha256MaloVaALaDlq() {
        UUID foto = id("foto-sha");
        Map<String, Object> datos = Fotos.datosPublicada(foto, id("hallazgo-7"), T1, Fotos.jpeg(50, 50), 50, 50);
        datos.put("sha256", "a".repeat(64));
        publicarFoto("evidencia.publicada", datos);

        assertThat(siguienteDeLaDlqDeFotos(Duration.ofSeconds(15))).isNotNull();
        assertThat(filas()).isZero();
    }

    @Test
    @DisplayName("coordenadas en un campo de texto: se descartan con ack, sin DLQ y sin guardar nada")
    void coordenadasSeDescartan() {
        UUID foto = id("foto-coordenadas");
        Map<String, Object> datos = Fotos.datosPublicada(foto, id("hallazgo-8"), T1, Fotos.jpeg(50, 50), 50, 50);
        datos.put("descripcion", "5.53214, -73.36712");
        publicarFoto("evidencia.publicada", datos);
        Map<String, Object> retirada = Fotos.datosRetirada(id("foto-coordenadas-2"), id("hallazgo-8"), T1);
        retirada.put("latitud", 5.5);
        publicarFoto("evidencia.retirada", retirada);
        sincronizar("coordenadas");

        assertThat(publica(foto)).isNull();
        assertThat(publica(id("foto-coordenadas-2"))).isNull();
        assertThat(siguienteDeLaDlqDeFotos(Duration.ofMillis(500))).isNull();
    }

    @Test
    @DisplayName("sin user_id del publicador (verificación activa): DLQ de fotos")
    void sinUserIdVaALaDlq() {
        UUID foto = id("foto-intruso");
        String json = Eventos.sobre("evidencia.publicada",
                Fotos.datosPublicada(foto, id("hallazgo-9"), T1, Fotos.jpeg(50, 50), 50, 50));
        rabbit.send("biohub.eventos", "evidencia.publicada", Eventos.mensaje(json, null));
        assertThat(siguienteDeLaDlqDeFotos(Duration.ofSeconds(15))).isNotNull();
        assertThat(publica(foto)).isNull();
    }

    @Test
    @DisplayName("el hallazgo.* no se enruta a la cola de fotos ni el evidencia.* a la de hallazgos")
    void lasColasNoSeMezclan() {
        UUID foto = id("foto-cola");
        publicarFoto("evidencia.publicada", Fotos.datosPublicada(foto, id("hallazgo-10"), T1, Fotos.jpeg(50, 50), 50, 50));
        UUID hallazgo = id("hallazgo-cola");
        publicar("hallazgo.registrado", Eventos.sobre("hallazgo.registrado",
                Eventos.datos(hallazgo, 1, "Atelopus ebenoides", "NO_AMENAZADA")));
        esperarPublica(foto, true);
        await().atMost(ESPERA).untilAsserted(() -> assertThat(jdbc.sql("SELECT COUNT(*) FROM ficha_publica")
                .query(Long.class).single()).isEqualTo(1));
        assertThat(siguienteDeLaDlq(Duration.ofMillis(300))).isNull();
        assertThat(siguienteDeLaDlqDeFotos(Duration.ofMillis(300))).isNull();
    }
}
