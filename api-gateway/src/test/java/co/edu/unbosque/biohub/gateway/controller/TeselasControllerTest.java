package co.edu.unbosque.biohub.gateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.unbosque.biohub.gateway.domain.ImagenTesela;
import co.edu.unbosque.biohub.gateway.domain.LimitadorPeticiones;
import co.edu.unbosque.biohub.gateway.domain.RegistroRevocaciones;
import co.edu.unbosque.biohub.gateway.domain.ResultadoLimite;
import co.edu.unbosque.biohub.gateway.domain.RolInterno;
import co.edu.unbosque.biohub.gateway.service.DatosRechazadosException;
import co.edu.unbosque.biohub.gateway.service.LimiteExcedidoException;
import co.edu.unbosque.biohub.gateway.service.ServicioApiKeys;
import co.edu.unbosque.biohub.gateway.service.ServicioTeselas;
import co.edu.unbosque.biohub.gateway.service.TeselaNoDisponibleException;
import co.edu.unbosque.biohub.gateway.soporte.SeguridadGatewayEnPrueba;

/** SEC-J-04: el proxy de teselas con la cadena de seguridad real. */
@WebMvcTest(TeselasController.class)
@SeguridadGatewayEnPrueba
class TeselasControllerTest {

    private static final String TOKEN = "Bearer t";
    private static final String RUTA = "/api/gateway/teselas/5/9/15.png";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioTeselas teselas;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ServicioApiKeys apiKeys;

    @MockitoBean
    private LimitadorPeticiones limitador;

    @MockitoBean
    private RegistroRevocaciones revocaciones;

    @BeforeEach
    void preparar() {
        given(limitador.consumir(anyString(), anyInt(), any()))
                .willReturn(new ResultadoLimite(true, 120, 119, Duration.ofSeconds(60)));
        given(teselas.obtener(anyString(), anyInt(), anyInt(), anyInt())).willReturn(new ImagenTesela(PNG, "image/png"));
    }

    private void tokenCon(String... roles) {
        given(jwtDecoder.decode(anyString())).willReturn(Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("sub-usuario")
                .claim("preferred_username", "alguien")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    }

    @Test
    void sinTokenDa401YNoSalePeticionAlOrigen() throws Exception {
        mvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        verify(teselas, never()).obtener(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void usuarioSinRolDa403() throws Exception {
        tokenCon("offline_access");

        mvc.perform(get(RUTA).header("Authorization", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ROL_INSUFICIENTE"));
    }

    @ParameterizedTest
    @EnumSource(RolInterno.class)
    void cadaRolInternoRecibeLaImagenConCachePrivada(RolInterno rol) throws Exception {
        tokenCon(rol.nombre());

        mvc.perform(get(RUTA).header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control", "max-age=86400, private"));
        verify(teselas).obtener("sub-usuario", 5, 9, 15);
    }

    @Test
    void zoomNoNumericoDa400() throws Exception {
        tokenCon("cientifico_alto_rango");

        mvc.perform(get("/api/gateway/teselas/abc/9/15.png").header("Authorization", TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teselaInvalidaDa400ConCodigo() throws Exception {
        tokenCon("cientifico_alto_rango");
        given(teselas.obtener(anyString(), anyInt(), anyInt(), anyInt()))
                .willThrow(new DatosRechazadosException("fuera del mapa"));

        mvc.perform(get("/api/gateway/teselas/13/0/0.png").header("Authorization", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"));
    }

    @Test
    void cupoAgotadoDa429ConRetryAfter() throws Exception {
        tokenCon("cientifico_alto_rango");
        given(teselas.obtener(anyString(), anyInt(), anyInt(), anyInt())).willThrow(new LimiteExcedidoException(30));

        mvc.perform(get(RUTA).header("Authorization", TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.codigo").value("LIMITE_EXCEDIDO"));
    }

    @Test
    void origenConRespuestaInvalidaDa502() throws Exception {
        tokenCon("cientifico_alto_rango");
        given(teselas.obtener(anyString(), anyInt(), anyInt(), anyInt()))
                .willThrow(new TeselaNoDisponibleException("no es imagen"));

        mvc.perform(get(RUTA).header("Authorization", TOKEN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.codigo").value("TESELA_NO_DISPONIBLE"));
    }

    @Test
    void soloSePermiteGet() throws Exception {
        tokenCon("administrador");

        mvc.perform(post(RUTA).header("Authorization", TOKEN)).andExpect(status().isForbidden());
        verify(teselas, never()).obtener(anyString(), anyInt(), anyInt(), anyInt());
    }
}
