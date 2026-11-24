package co.edu.unbosque.biohub.consultapublica.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import co.edu.unbosque.biohub.consultapublica.config.CacheConfig;

/**
 * Invalida lo mínimo tras aplicar un evento (ARQ-R-5 / pendientes de Juanita: antes cada evento vaciaba tres cachés
 * completas con SCAN):
 * <ul>
 *   <li>{@code hallazgo.*}: solo {@code fichas::<id>}. Las búsquedas, el catálogo y las familias no tienen una clave por
 *       ficha (dependen de {@code q}, filtros y página), así que quedan con su TTL corto (60 s en Redis y 30 s de
 *       {@code Cache-Control}): un dato viejo vive como máximo ese tiempo, y es lo que ya se aceptaba cuando Redis
 *       fallaba (D25). La ficha, que es lo que alguien abre tras un cambio, se ve al instante.</li>
 *   <li>{@code evidencia.*} (D66): se vacía solo {@code especies} (el catálogo lleva {@code fotoId}). Es un evento
 *       raro y decidido por una persona, no una carga masiva; las búsquedas y las familias no llevan fotos.</li>
 * </ul>
 * Si Redis falla solo se registra un WARN: el dato viejo vive como máximo un TTL y el evento NO debe fallar por eso.
 */
@Component
public class InvalidadorCacheFichas {

    private static final Logger log = LoggerFactory.getLogger(InvalidadorCacheFichas.class);

    private final CacheManager cacheManager;

    public InvalidadorCacheFichas(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** Un {@code hallazgo.*} cambió la ficha: se retira solo esa ficha de la caché. */
    public void invalidar(UUID hallazgoId) {
        try {
            Cache fichas = cacheManager.getCache(CacheConfig.FICHAS);
            if (fichas != null) {
                fichas.evict(hallazgoId.toString());
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo invalidar la caché hallazgoId={} causa={}", hallazgoId, e.getClass().getSimpleName());
        }
    }

    /** Una foto pública cambió (publicada o retirada): se vacía el catálogo, que es lo único que lleva {@code fotoId}. */
    public void invalidarCatalogoPorFoto(UUID evidenciaId) {
        try {
            Cache especies = cacheManager.getCache(CacheConfig.ESPECIES);
            if (especies != null) {
                especies.clear();
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo invalidar el catálogo evidenciaId={} causa={}", evidenciaId, e.getClass().getSimpleName());
        }
    }
}
