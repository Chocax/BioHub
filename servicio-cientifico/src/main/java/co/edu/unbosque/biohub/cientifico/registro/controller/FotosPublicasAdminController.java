package co.edu.unbosque.biohub.cientifico.registro.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unbosque.biohub.cientifico.registro.service.RepublicacionEnCursoException;
import co.edu.unbosque.biohub.cientifico.registro.service.RepublicadorFotosPublicas;

/**
 * Administración de las fotos públicas (D66, 04 §13.10): republicación de {@code evidencia.publicada}.
 */
@RestController
@RequestMapping("/api/cientifico/admin/fotos-publicas")
public class FotosPublicasAdminController {

    private final RepublicadorFotosPublicas republicador;

    public FotosPublicasAdminController(RepublicadorFotosPublicas republicador) {
        this.republicador = republicador;
    }

    /**
     * Vuelve a publicar todas las fotos marcadas como públicas (genera las miniaturas que falten). Responde
     * enseguida con 202 y el número de fotos; el envío ocurre en segundo plano. Si ya hay una republicación en curso
     * responde 409 y no inicia otra (SEC-I27-7).
     */
    @PostMapping("/republicar")
    @PreAuthorize("hasRole('administrador')")
    public ResponseEntity<Map<String, Integer>> republicar(@AuthenticationPrincipal Jwt jwt) {
        var resultado = republicador.republicarTodas(jwt.getClaimAsString("preferred_username"));
        if (resultado.yaEnCurso()) {
            throw new RepublicacionEnCursoException(); // 409: no se inició otra
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("fotos", resultado.fotos()));
    }
}
