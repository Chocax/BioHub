package co.edu.unbosque.biohub.consultapublica.controller;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import co.edu.unbosque.biohub.consultapublica.service.ConsultaEspeciesService;
import co.edu.unbosque.biohub.consultapublica.service.CriterioBusquedaPublica;
import co.edu.unbosque.biohub.consultapublica.service.ErrorCampo;
import co.edu.unbosque.biohub.consultapublica.service.FotosEspeciesService;
import co.edu.unbosque.biohub.consultapublica.service.ParametroInvalidoException;
import co.edu.unbosque.biohub.consultapublica.service.ValidadorBusqueda;
import co.edu.unbosque.biohub.consultapublica.service.vista.FamiliasResponse;
import co.edu.unbosque.biohub.consultapublica.service.vista.PaginaEspeciesResponse;

/**
 * Catálogo público por especie, familias y foto pública (contrato 04, §4.4, D65). Sin autenticación (D16) y sin
 * coordenadas (D14). Los filtros se validan igual que en {@code /api/publica/fichas}.
 */
@RestController
@RequestMapping("/api/publica/especies")
public class EspeciesPublicasController {

    /** Letras (con tildes), espacios, punto, guion y apóstrofo: lo que usan los nombres científicos. */
    private static final Pattern NOMBRE = Pattern.compile("^[\\p{L} .'-]{2,150}$");

    private final ValidadorBusqueda validador;
    private final ConsultaEspeciesService especies;
    private final FotosEspeciesService fotos;

    public EspeciesPublicasController(ValidadorBusqueda validador, ConsultaEspeciesService especies,
                                      FotosEspeciesService fotos) {
        this.validador = validador;
        this.especies = especies;
        this.fotos = fotos;
    }

    @GetMapping
    @PreAuthorize("permitAll()")
    public ResponseEntity<PaginaEspeciesResponse> buscar(@RequestParam(required = false) String q,
                                                         @RequestParam(required = false) String familia,
                                                         @RequestParam(required = false) String departamento,
                                                         @RequestParam(required = false) String page,
                                                         @RequestParam(required = false) String size) {
        CriterioBusquedaPublica criterio = validador.validar(q, familia, departamento, page, size);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=30")
                .body(especies.buscar(criterio));
    }

    @GetMapping("/familias")
    @PreAuthorize("permitAll()")
    public ResponseEntity<FamiliasResponse> familias() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .body(especies.familias());
    }

    /** JPEG de la foto pública; 404 si la especie no tiene (el portal muestra una ilustración). */
    @GetMapping("/foto")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> foto(@RequestParam(required = false) String nombre) {
        if (nombre == null || !NOMBRE.matcher(nombre.trim()).matches()) {
            throw new ParametroInvalidoException(List.of(new ErrorCampo("nombre", "nombre científico inválido")));
        }
        return fotos.foto(nombre)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound()
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(2)).cachePublic())
                        .build());
    }
}
