package co.edu.unbosque.biohub.cientifico.registro.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import co.edu.unbosque.biohub.cientifico.registro.domain.CatalogoEspecies;
import co.edu.unbosque.biohub.cientifico.registro.service.CatalogoEspeciesService;

/**
 * Galería de especies y listas desplegables de la web interna (contrato 04 §13.10, D65). Nunca devuelve
 * coordenadas: esas siguen solo en {@code /api/cientifico/ubicacion/**} para el alto rango.
 */
@RestController
@RequestMapping("/api/cientifico/especies")
public class EspeciesController {

    private final CatalogoEspeciesService catalogo;

    public EspeciesController(CatalogoEspeciesService catalogo) {
        this.catalogo = catalogo;
    }

    /** Una tarjeta por especie, con su foto más reciente y su ficha. Los mismos roles que ven las fichas. */
    @GetMapping
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango', 'auditor_cientifico', 'administrador')")
    public CatalogoEspeciesService.Pagina buscar(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String familia,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return catalogo.buscar(q, familia, page, size);
    }

    /** Especies, familias e investigadores ya registrados, para autocompletar. Todos los roles internos buscan. */
    @GetMapping("/sugerencias")
    @PreAuthorize("hasAnyRole('investigador_campo', 'cientifico_alto_rango', 'auditor_cientifico', 'administrador',"
            + " 'analista_ambiental')")
    public CatalogoEspecies.Sugerencias sugerencias() {
        return catalogo.sugerencias();
    }
}
