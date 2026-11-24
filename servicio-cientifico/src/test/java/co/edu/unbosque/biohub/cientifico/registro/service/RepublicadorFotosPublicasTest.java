package co.edu.unbosque.biohub.cientifico.registro.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.ServletWebRequest;

import co.edu.unbosque.biohub.cientifico.registro.controller.FotosPublicasAdminController;
import co.edu.unbosque.biohub.cientifico.registro.controller.RegistroExceptionHandler;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgoRepository;

/** SEC-I27-7: la republicación deja constancia de quién la pidió y responde 409 si ya hay una en curso. */
class RepublicadorFotosPublicasTest {

    private final UUID idFoto = UUID.randomUUID();
    private EvidenciaHallazgoRepository evidencias;
    private FotosPublicas fotos;
    private final List<Runnable> pendientes = new ArrayList<>();
    private RepublicadorFotosPublicas republicador;

    @BeforeEach
    void preparar() {
        evidencias = mock(EvidenciaHallazgoRepository.class);
        fotos = mock(FotosPublicas.class);
        when(evidencias.idsFotosPublicas()).thenReturn(List.of(idFoto));
        Executor manual = pendientes::add; // no ejecuta: simula una republicación que sigue en curso
        republicador = new RepublicadorFotosPublicas(evidencias, fotos, mock(PlatformTransactionManager.class), manual);
    }

    private static Jwt jwt(String usuario) {
        return Jwt.withTokenValue("t").header("alg", "none").claim("preferred_username", usuario)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    @DisplayName("la segunda petición con una en curso no inicia otra y lo indica")
    void segundaPeticionEnCurso() {
        var primera = republicador.republicarTodas("admin1");
        var segunda = republicador.republicarTodas("admin2");

        assertThat(primera.yaEnCurso()).isFalse();
        assertThat(primera.fotos()).isEqualTo(1);
        assertThat(segunda.yaEnCurso()).isTrue();
        assertThat(pendientes).hasSize(1);
    }

    @Test
    @DisplayName("el controlador responde 409 problem+json si ya hay una en curso")
    void controladorDevuelve409() {
        var controlador = new FotosPublicasAdminController(republicador);

        assertThat(controlador.republicar(jwt("admin1")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThatThrownBy(() -> controlador.republicar(jwt("admin2")))
                .isInstanceOf(RepublicacionEnCursoException.class);

        var respuesta = new RegistroExceptionHandler().republicacionEnCurso(new RepublicacionEnCursoException(),
                new ServletWebRequest(new org.springframework.mock.web.MockHttpServletRequest(
                        "POST", "/api/cientifico/admin/fotos-publicas/republicar")));
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respuesta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(respuesta.getBody()).isNotNull();
    }

    @Test
    @DisplayName("al terminar se puede pedir otra, y las fotos se anuncian a nombre del administrador")
    void terminadaSePuedeVolverAPedir() {
        EvidenciaHallazgo evidencia = mock(EvidenciaHallazgo.class);
        when(evidencia.isPublica()).thenReturn(true);
        when(evidencias.findById(idFoto)).thenReturn(Optional.of(evidencia));

        republicador.republicarTodas("admin1");
        pendientes.get(0).run();

        verify(fotos).anunciarPublicada(eq(evidencia), eq("admin1"));
        assertThat(republicador.republicarTodas("admin2").yaEnCurso()).isFalse();
        verify(evidencias, org.mockito.Mockito.atLeastOnce()).save(any());
    }
}
