package co.edu.unbosque.biohub.cientifico.registro.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unbosque.biohub.cientifico.eventos.domain.EventoSobre;
import co.edu.unbosque.biohub.cientifico.registro.domain.Hallazgo;
import co.edu.unbosque.biohub.cientifico.registro.domain.HallazgoRepository;
import co.edu.unbosque.biohub.cientifico.registro.service.HallazgoNoEncontradoException;
import co.edu.unbosque.biohub.cientifico.registro.service.HallazgoService;
import co.edu.unbosque.biohub.cientifico.registro.service.RepublicadorEventosHallazgoService;
import co.edu.unbosque.biohub.cientifico.registro.service.ReclasificarHallazgoComando;
import co.edu.unbosque.biohub.cientifico.registro.service.RegistrarHallazgoComando;
import co.edu.unbosque.biohub.cientifico.registro.service.ResultadoRegistro;

/**
 * API del módulo registro (HU-01, HU-03). Entrada REST sin lógica de negocio: valida el rol, toma el
 * usuario del JWT y delega en el servicio.
 *
 * <p>Los roles los valida este servicio, no solo el gateway (defensa en profundidad, RNF6): quien
 * registra en campo es {@code investigador_campo}, y reclasificar exige rol científico, que es lo que
 * pide HU-03.
 */
@RestController
@RequestMapping("/api/cientifico/hallazgos")
public class HallazgoController {

    private final HallazgoService hallazgoService;
    private final HallazgoRepository hallazgos;
    private final RepublicadorEventosHallazgoService republicador;

    public HallazgoController(HallazgoService hallazgoService, HallazgoRepository hallazgos,
                              RepublicadorEventosHallazgoService republicador) {
        this.hallazgoService = hallazgoService;
        this.hallazgos = hallazgos;
        this.republicador = republicador;
    }

    /** Registra un hallazgo de campo (HU-01). */
    @PostMapping
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango')")
    public ResponseEntity<RegistroHallazgoResponse> registrar(@RequestBody RegistrarHallazgoRequest peticion,
                                                              @RequestHeader("Idempotency-Key") String claveIdempotencia,
                                                              @AuthenticationPrincipal Jwt jwt) {
        RegistrarHallazgoComando comando = new RegistrarHallazgoComando(
                peticion.nombreCientifico(), peticion.nombreComun(), peticion.familia(), peticion.tipoEspecimen(),
                peticion.fechaRecoleccion(), peticion.departamento(), peticion.categoriaUicn(),
                peticion.descripcion(), peticion.latitud(), peticion.longitud(), peticion.atributos());

        ResultadoRegistro resultado = hallazgoService.registrar(comando, usuario(jwt), claveIdempotencia);
        return ResponseEntity
                .created(URI.create("/api/cientifico/hallazgos/" + resultado.hallazgoId()))
                .body(RegistroHallazgoResponse.de(resultado));
    }

    /**
     * Reclasifica o corrige un hallazgo existente (HU-03). Exige rol científico y justificación.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango')")
    public RegistroHallazgoResponse reclasificar(@PathVariable UUID id,
                                                 @RequestBody ReclasificarHallazgoRequest peticion,
                                                 @AuthenticationPrincipal Jwt jwt) {
        ReclasificarHallazgoComando comando = new ReclasificarHallazgoComando(
                peticion.nombreCientifico(), peticion.nombreComun(), peticion.familia(), peticion.tipoEspecimen(),
                peticion.fechaRecoleccion(), peticion.departamento(), peticion.categoriaUicn(),
                peticion.descripcion(), peticion.atributos(), peticion.justificacion());

        return RegistroHallazgoResponse.de(hallazgoService.reclasificar(id, comando, usuario(jwt)));
    }

    /** Republica el evento correspondiente a una versión ya persistida (solo administración). */
    @PostMapping("/{id}/versiones/{version}/republicar-evento")
    @PreAuthorize("hasRole('administrador')")
    public ResponseEntity<EventoSobre> republicarEvento(@PathVariable UUID id, @PathVariable int version) {
        return ResponseEntity.accepted().body(republicador.republicar(id, version));
    }

    /** Consulta un hallazgo por su identificador. Sin coordenadas: para eso están los endpoints de ubicación. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango', 'auditor_cientifico', 'administrador')")
    public HallazgoResponse consultar(@PathVariable UUID id) {
        Hallazgo hallazgo = hallazgos.findById(id).orElseThrow(() -> new HallazgoNoEncontradoException(id));
        return HallazgoResponse.de(hallazgo);
    }

    /**
     * {@code preferred_username} del JWT; es el mismo valor por el que filtra la búsqueda interna. Sirve como ID del
     * usuario (HU-11 c.3) porque en el realm es único y no se puede cambiar (D59).
     */
    static String usuario(Jwt jwt) {
        String nombre = jwt.getClaimAsString("preferred_username");
        return nombre != null ? nombre : jwt.getSubject();
    }
}
