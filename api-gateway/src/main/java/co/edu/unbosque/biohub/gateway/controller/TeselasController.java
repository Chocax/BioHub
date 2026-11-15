package co.edu.unbosque.biohub.gateway.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unbosque.biohub.gateway.config.PropiedadesTeselas;
import co.edu.unbosque.biohub.gateway.config.RutasGateway;
import co.edu.unbosque.biohub.gateway.domain.ImagenTesela;
import co.edu.unbosque.biohub.gateway.service.ServicioTeselas;

/**
 * Teselas del mapa de web-interna a través del gateway (SEC-J-04). Uso desde Leaflet:
 * {@code /api/gateway/teselas/{z}/{x}/{y}.png} con el JWT en {@code Authorization}
 * (ver 11-seguridad-y-acceso.md §7).
 */
@RestController
public class TeselasController {

    private final ServicioTeselas teselas;
    private final CacheControl cacheNavegador;

    public TeselasController(ServicioTeselas teselas, PropiedadesTeselas propiedades) {
        this.teselas = teselas;
        // private: el navegador del usuario puede guardarla, una caché compartida no.
        this.cacheNavegador = CacheControl.maxAge(propiedades.vidaNavegador()).cachePrivate();
    }

    @GetMapping(RutasGateway.TESELAS + "/{z}/{x}/{y:\\d+}.png")
    public ResponseEntity<byte[]> tesela(@PathVariable int z, @PathVariable int x, @PathVariable int y,
                                         @AuthenticationPrincipal Jwt usuario) {
        ImagenTesela imagen = teselas.obtener(usuario.getSubject(), z, x, y);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(imagen.tipoContenido()))
                .cacheControl(cacheNavegador)
                .body(imagen.bytes());
    }
}
