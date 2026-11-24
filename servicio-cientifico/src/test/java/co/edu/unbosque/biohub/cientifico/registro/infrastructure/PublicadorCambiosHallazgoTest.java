package co.edu.unbosque.biohub.cientifico.registro.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.unbosque.biohub.cientifico.eventos.service.EventoNoPublicadoException;
import co.edu.unbosque.biohub.cientifico.eventos.service.PublicadorEventos;
import co.edu.unbosque.biohub.cientifico.registro.service.CambioHallazgoConfirmado;

/** BE-R-1: ningún listener AFTER_COMMIT deja escapar una excepción (el commit ya ocurrió: un 500 duplicaría el alta). */
class PublicadorCambiosHallazgoTest {

    private final PublicadorEventos publicadorEventos = mock(PublicadorEventos.class);
    private final PublicadorCambiosHallazgo listener = new PublicadorCambiosHallazgo(publicadorEventos);
    private final CambioHallazgoConfirmado cambio = new CambioHallazgoConfirmado("hallazgo.registrado", "inv1",
            Map.of("hallazgoId", "abc"));

    @Test
    @DisplayName("publica con el tipo, el usuario y los datos del cambio")
    void publica() {
        listener.publicar(cambio);

        verify(publicadorEventos).publicar(eq("hallazgo.registrado"), eq("inv1"), eq(Map.of("hallazgoId", "abc")));
    }

    @Test
    @DisplayName("si el broker no confirma, no propaga")
    void noPropagaBrokerCaido() {
        when(publicadorEventos.publicar(any(), any(), anyMap())).thenThrow(new EventoNoPublicadoException(new RuntimeException()));

        assertThatCode(() -> listener.publicar(cambio)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("si la guarda de coordenadas lanza IllegalArgumentException, no propaga")
    void noPropagaGuardaDeCoordenadas() {
        when(publicadorEventos.publicar(any(), any(), anyMap()))
                .thenThrow(new IllegalArgumentException("hallazgo.* no puede llevar coordenadas (D14)"));

        assertThatCode(() -> listener.publicar(cambio)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cualquier otra RuntimeException tampoco se propaga (también con eventos evidencia.*)")
    void noPropagaNingunaRuntimeException() {
        when(publicadorEventos.publicar(any(), any(), anyMap())).thenThrow(new IllegalStateException("boom"));
        CambioHallazgoConfirmado foto = new CambioHallazgoConfirmado("evidencia.publicada", "inv1",
                Map.of("evidenciaId", "x", "hallazgoId", "y"));

        assertThatCode(() -> listener.publicar(cambio)).doesNotThrowAnyException();
        assertThatCode(() -> listener.publicar(foto)).doesNotThrowAnyException();
    }
}
