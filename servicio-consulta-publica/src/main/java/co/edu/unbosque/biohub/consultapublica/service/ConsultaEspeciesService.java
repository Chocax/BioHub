package co.edu.unbosque.biohub.consultapublica.service;

import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import co.edu.unbosque.biohub.consultapublica.config.CacheConfig;
import co.edu.unbosque.biohub.consultapublica.infrastructure.BusquedaEspeciesJdbc;
import co.edu.unbosque.biohub.consultapublica.service.vista.EspecieResumenResponse;
import co.edu.unbosque.biohub.consultapublica.service.vista.FamiliasResponse;
import co.edu.unbosque.biohub.consultapublica.service.vista.PaginaEspeciesResponse;

/**
 * Catálogo público por especie y lista de familias (D65), con la misma caché tolerante que las fichas: expira por TTL
 * corto, un evento de foto vacía el catálogo ({@link InvalidadorCacheFichas}) y si Redis falla se lee de MySQL.
 */
@Service
public class ConsultaEspeciesService {

    /** Tope de familias del desplegable: hoy son decenas; con miles, el filtro sigue aceptando texto libre. */
    static final int TOPE_FAMILIAS = 500;

    private final BusquedaEspeciesJdbc repositorio;

    public ConsultaEspeciesService(BusquedaEspeciesJdbc repositorio) {
        this.repositorio = repositorio;
    }

    @Cacheable(cacheNames = CacheConfig.ESPECIES, key = "#criterio.claveCache()", sync = true)
    public PaginaEspeciesResponse buscar(CriterioBusquedaPublica criterio) {
        long total = repositorio.contar(criterio);
        List<EspecieResumenResponse> contenido = total > criterio.offset() ? repositorio.buscar(criterio) : List.of();
        int totalPaginas = (int) ((total + criterio.size() - 1) / criterio.size());
        return new PaginaEspeciesResponse(contenido, criterio.page(), criterio.size(), total, totalPaginas,
                criterio.modoDegradado(), criterio.filtrosIgnorados());
    }

    /**
     * Las familias del desplegable. En modo degradado (ARQ-R-5) la lista va vacía y no toca la base: es una función
     * secundaria que se apaga con la carga (04 §4.4). La clave de la caché incluye el modo, así una respuesta
     * degradada no queda como la normal cuando la carga baja.
     */
    @Cacheable(cacheNames = CacheConfig.FAMILIAS, key = "#degradado", sync = true)
    public FamiliasResponse familias(boolean degradado) {
        return new FamiliasResponse(degradado ? List.of() : repositorio.familias(TOPE_FAMILIAS));
    }
}
