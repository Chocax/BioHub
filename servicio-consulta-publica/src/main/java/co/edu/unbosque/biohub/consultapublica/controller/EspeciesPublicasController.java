package co.edu.unbosque.biohub.consultapublica.controller;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import co.edu.unbosque.biohub.consultapublica.domain.FotoServida;
import co.edu.unbosque.biohub.consultapublica.service.ElectorEstrategiaBusqueda;
import co.edu.unbosque.biohub.consultapublica.service.FotoNoEncontradaException;
import co.edu.unbosque.biohub.consultapublica.service.ConsultaEspeciesService;
import co.edu.unbosque.biohub.consultapublica.service.CriterioBusquedaPublica;
import co.edu.unbosque.biohub.consultapublica.service.ErrorCampo;
import co.edu.unbosque.biohub.consultapublica.service.FotosEspeciesService;
import co.edu.unbosque.biohub.consultapublica.service.ParametroInvalidoException;
import co.edu.unbosque.biohub.consultapublica.service.ValidadorBusqueda;
import co.edu.unbosque.biohub.consultapublica.service.vista.FamiliasResponse;
import co.edu.unbosque.biohub.consultapublica.service.vista.PaginaEspeciesResponse;

/**
 * Catálogo público por especie, familias y foto pública (contrato 04, §4.4, D65, D66). Sin autenticación (D16) y sin
 * coordenadas (D14). Los filtros se validan igual que en {@code /api/publica/fichas}. Las fotos salen de la base de
 * lectura (llegan por el evento {@code evidencia.publicada}); este controlador no llama a ningún otro servicio.
 */
@RestController
@RequestMapping("/api/publica/especies")
public class EspeciesPublicasController {

    /** Letras (con tildes), espacios, punto, guion y apóstrofo: lo que usan los nombres científicos. */
    private static final Pattern NOMBRE = Pattern.compile("^[\\p{L} .'-]{2,150}$");
    private static final Pattern UUID_CANONICO =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String INMUTABLE = "public, max-age=31536000, immutable";

    private final ValidadorBusqueda validador;
    private final ConsultaEspeciesService especies;
    private final FotosEspeciesService fotos;
    private final ElectorEstrategiaBusqueda elector;

    public EspeciesPublicasController(ValidadorBusqueda validador, ConsultaEspeciesService especies,
                                      FotosEspeciesService fotos, ElectorEstrategiaBusqueda elector) {
        this.validador = validador;
        this.especies = especies;
        this.fotos = fotos;
        this.elector = elector;
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
                .body(especies.familias(elector.degradado()));
    }

    /**
     * La miniatura pública por su id (D66): inmutable y con el sha256 como ETag. 404 si no existe o ya no es pública;
     * 400 si {@code fotoId} no es un UUID. Con {@code If-None-Match} vigente responde 304 sin leer los bytes.
     */
    @GetMapping("/fotos/{fotoId}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> fotoPorId(@PathVariable String fotoId,
                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                            String siNoCoincide) {
        if (fotoId == null || !UUID_CANONICO.matcher(fotoId).matches()) {
            throw new ParametroInvalidoException(List.of(new ErrorCampo("fotoId", "identificador inválido")));
        }
        UUID id = UUID.fromString(fotoId);
        String sha = fotos.sha256(id).orElseThrow(FotoNoEncontradaException::new);
        if (coincide(siNoCoincide, sha)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag(sha))
                    .header(HttpHeaders.CACHE_CONTROL, INMUTABLE)
                    .build();
        }
        FotoServida foto = fotos.porId(id).orElseThrow(FotoNoEncontradaException::new);
        return jpeg(foto, INMUTABLE);
    }

    /** Compatibilidad: la foto pública más reciente de la especie, de la base de lectura; 404 si no hay. */
    @GetMapping("/foto")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> foto(@RequestParam(required = false) String nombre) {
        if (nombre == null || !NOMBRE.matcher(nombre.trim()).matches()) {
            throw new ParametroInvalidoException(List.of(new ErrorCampo("nombre", "nombre científico inválido")));
        }
        FotoServida foto = fotos.porNombre(nombre).orElseThrow(FotoNoEncontradaException::new);
        return jpeg(foto, "public, max-age=300");
    }

    private static ResponseEntity<byte[]> jpeg(FotoServida foto, String cacheControl) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .eTag(etag(foto.sha256()))
                .header("X-Content-Type-Options", "nosniff")
                .body(foto.bytes());
    }

    private static String etag(String sha256) {
        return "\"" + sha256 + "\"";
    }

    /** {@code If-None-Match} contiene el ETag actual (o {@code *}); la comparación débil ignora el prefijo {@code W/}. */
    static boolean coincide(String siNoCoincide, String sha256) {
        if (siNoCoincide == null || siNoCoincide.isBlank()) {
            return false;
        }
        for (String candidato : siNoCoincide.split(",")) {
            String c = candidato.strip();
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals("*") || c.equals(etag(sha256))) {
                return true;
            }
        }
        return false;
    }
}
