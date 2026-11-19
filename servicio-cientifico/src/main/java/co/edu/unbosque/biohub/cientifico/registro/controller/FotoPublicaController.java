package co.edu.unbosque.biohub.cientifico.registro.controller;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import co.edu.unbosque.biohub.cientifico.registro.service.EvidenciaService;

/**
 * Foto pública de una especie para el Servicio de Consulta Pública (D65). No exige token: solo entrega fotografías
 * que un investigador marcó como públicas, reducidas y sin metadatos, que de todas formas se publican en el portal.
 * El servicio solo escucha en la red interna y el gateway exige rol en {@code /api/cientifico/**}, así que desde
 * internet se llega a la foto únicamente por {@code /api/publica/especies/foto}.
 */
@RestController
@RequestMapping("/api/cientifico/publico/especies")
public class FotoPublicaController {

    private static final Pattern NOMBRE = Pattern.compile("^[\\p{L} .'-]{2,150}$");

    private final EvidenciaService evidencias;

    public FotoPublicaController(EvidenciaService evidencias) {
        this.evidencias = evidencias;
    }

    @GetMapping("/foto")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> foto(@RequestParam(required = false) String nombre) {
        if (nombre == null || !NOMBRE.matcher(nombre.trim()).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return evidencias.fotoPublicaDeEspecie(nombre)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)))
                        .header("X-Content-Type-Options", "nosniff")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
