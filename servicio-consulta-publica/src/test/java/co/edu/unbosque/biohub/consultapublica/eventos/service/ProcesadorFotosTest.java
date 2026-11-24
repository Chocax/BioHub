package co.edu.unbosque.biohub.consultapublica.eventos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import co.edu.unbosque.biohub.consultapublica.domain.FotoPublica;
import co.edu.unbosque.biohub.consultapublica.domain.ResultadoProyeccion;
import co.edu.unbosque.biohub.consultapublica.eventos.domain.EventoSobre;
import co.edu.unbosque.biohub.consultapublica.service.InvalidadorCacheFichas;
import co.edu.unbosque.biohub.consultapublica.service.ProyectorFotos;
import co.edu.unbosque.biohub.consultapublica.soporte.Fotos;

class ProcesadorFotosTest {

    private static final Instant AHORA = Instant.parse("2026-11-21T12:00:00Z");
    private static final String CAMBIO = "2026-11-21T10:00:00.123456789Z";

    private final ProyectorFotos proyector = mock(ProyectorFotos.class);
    private final InvalidadorCacheFichas invalidador = mock(InvalidadorCacheFichas.class);
    private ProcesadorFotos procesador;
    private final UUID evidencia = UUID.randomUUID();
    private final UUID hallazgo = UUID.randomUUID();
    private final byte[] jpeg = Fotos.jpeg(50, 40);

    @BeforeEach
    void preparar() {
        procesador = new ProcesadorFotos(proyector, invalidador, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private static EventoSobre sobre(String tipo, Map<String, Object> datos) {
        return new EventoSobre(UUID.randomUUID(), tipo, "servicio-cientifico", AHORA, "inv", datos);
    }

    @Test
    void publicadaValidaProyectaEInvalidaElCatalogoSoloSiHuboCambio() {
        when(proyector.publicar(any())).thenReturn(ResultadoProyeccion.CREADA);
        procesador.procesar(sobre("evidencia.publicada", Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 50, 40)));

        ArgumentCaptor<FotoPublica> foto = ArgumentCaptor.forClass(FotoPublica.class);
        verify(proyector).publicar(foto.capture());
        assertThat(foto.getValue().evidenciaId()).isEqualTo(evidencia);
        assertThat(foto.getValue().hallazgoId()).isEqualTo(hallazgo);
        assertThat(foto.getValue().bytes()).isEqualTo(jpeg);
        assertThat(foto.getValue().sha256()).isEqualTo(Fotos.sha256(jpeg));
        assertThat(foto.getValue().cambioEn()).isEqualTo(Instant.parse(CAMBIO));
        verify(invalidador).invalidarCatalogoPorFoto(evidencia);
    }

    @Test
    void unEventoIgnoradoNoInvalidaNada() {
        when(proyector.publicar(any())).thenReturn(ResultadoProyeccion.IGNORADA);
        procesador.procesar(sobre("evidencia.publicada", Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 50, 40)));
        verify(invalidador, never()).invalidarCatalogoPorFoto(any());
    }

    @Test
    void retiradaSeProyectaSinTocarLosBytes() {
        when(proyector.retirar(any(), any(), any())).thenReturn(ResultadoProyeccion.ACTUALIZADA);
        procesador.procesar(sobre("evidencia.retirada", Fotos.datosRetirada(evidencia, hallazgo, CAMBIO)));
        verify(proyector).retirar(evidencia, hallazgo, Instant.parse(CAMBIO));
        verify(invalidador).invalidarCatalogoPorFoto(evidencia);
    }

    @Test
    void datosInvalidosVanAlaDlqSinProyectar() {
        Map<String, Object> sinSha = Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 50, 40);
        sinSha.put("sha256", "xyz");
        Map<String, Object> anchoGrande = Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 1201, 40);
        Map<String, Object> anchoDecimal = Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 50, 40);
        anchoDecimal.put("ancho", 50.5);
        Map<String, Object> idMalo = Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 50, 40);
        idMalo.put("evidenciaId", "no-es-uuid");
        Map<String, Object> fechaMala = Fotos.datosPublicada(evidencia, hallazgo, "ayer", jpeg, 50, 40);
        Map<String, Object> futuro = Fotos.datosPublicada(evidencia, hallazgo, "2099-01-01T00:00:00Z", jpeg, 50, 40);
        Map<String, Object> sinJpeg = Fotos.datosPublicada(evidencia, hallazgo, CAMBIO, jpeg, 50, 40);
        sinJpeg.remove("jpegBase64");
        for (Map<String, Object> d : java.util.List.of(sinSha, anchoGrande, anchoDecimal, idMalo, fechaMala, futuro, sinJpeg)) {
            assertThatThrownBy(() -> procesador.procesar(sobre("evidencia.publicada", d)))
                    .isInstanceOf(EventoInvalidoException.class);
        }
        assertThatThrownBy(() -> procesador.procesar(sobre("evidencia.retirada", Map.of("evidenciaId", evidencia.toString()))))
                .isInstanceOf(EventoInvalidoException.class);
        verifyNoInteractions(proyector, invalidador);
    }
}
