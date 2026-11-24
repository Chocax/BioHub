package co.edu.unbosque.biohub.consultapublica.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import co.edu.unbosque.biohub.consultapublica.soporte.CacheRota;

class InvalidadorCacheFichasTest {

    @Test
    void unHallazgoEvictaSoloSuFichaYDejaLosListadosConSuTtl() {
        CacheManager cm = new ConcurrentMapCacheManager("fichas", "busquedas", "especies", "familias");
        UUID id = UUID.randomUUID();
        UUID otro = UUID.randomUUID();
        cm.getCache("fichas").put(id.toString(), "a");
        cm.getCache("fichas").put(otro.toString(), "b");
        cm.getCache("busquedas").put("q=x", "r1");
        cm.getCache("busquedas").put("q=y", "r2");

        new InvalidadorCacheFichas(cm).invalidar(id);

        assertThat(cm.getCache("fichas").get(id.toString())).isNull();
        assertThat(cm.getCache("fichas").get(otro.toString())).isNotNull();
        assertThat(cm.getCache("busquedas").get("q=x")).isNotNull();
        assertThat(cm.getCache("busquedas").get("q=y")).isNotNull();
    }

    @Test
    void unaFotoVaciaSoloElCatalogo() {
        CacheManager cm = new ConcurrentMapCacheManager("fichas", "busquedas", "especies", "familias");
        UUID id = UUID.randomUUID();
        cm.getCache("fichas").put(id.toString(), "a");
        cm.getCache("busquedas").put("q=x", "r1");
        cm.getCache("especies").put("p=0", "e1");
        cm.getCache("especies").put("p=1", "e2");
        cm.getCache("familias").put("false", "f");

        new InvalidadorCacheFichas(cm).invalidarCatalogoPorFoto(id);

        assertThat(cm.getCache("especies").get("p=0")).isNull();
        assertThat(cm.getCache("especies").get("p=1")).isNull();
        assertThat(cm.getCache("fichas").get(id.toString())).isNotNull();
        assertThat(cm.getCache("busquedas").get("q=x")).isNotNull();
        assertThat(cm.getCache("familias").get("false")).isNotNull();
    }

    @Test
    void siLaCacheFallaNoLanzaParaNoHacerFallarElEvento() {
        CacheManager roto = new CacheManager() {
            @Override
            public org.springframework.cache.Cache getCache(String name) {
                return new CacheRota();
            }

            @Override
            public java.util.Collection<String> getCacheNames() {
                return java.util.List.of();
            }
        };
        assertThatCode(() -> new InvalidadorCacheFichas(roto).invalidar(UUID.randomUUID())).doesNotThrowAnyException();
        assertThatCode(() -> new InvalidadorCacheFichas(roto).invalidarCatalogoPorFoto(UUID.randomUUID()))
                .doesNotThrowAnyException();
    }
}
