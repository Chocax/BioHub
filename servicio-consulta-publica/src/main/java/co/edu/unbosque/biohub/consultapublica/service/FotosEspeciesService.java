package co.edu.unbosque.biohub.consultapublica.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import co.edu.unbosque.biohub.consultapublica.infrastructure.FotosCientificoHttp;

/**
 * Fotos públicas de las especies (D65) con una caché en memoria acotada: en un pico de tráfico el portal no le
 * pide la misma foto al Servicio Científico una y otra vez. Guarda también los "no tiene foto" (menos tiempo),
 * para que una especie sin foto no genere una petición interna por visita.
 */
@Service
public class FotosEspeciesService {

    static final int MAXIMO_ENTRADAS = 120;
    static final Duration VIGENCIA_FOTO = Duration.ofMinutes(10);
    static final Duration VIGENCIA_SIN_FOTO = Duration.ofMinutes(2);

    private final FotosCientificoHttp cientifico;
    private final Clock reloj;
    private final Map<String, Entrada> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entrada> mayor) {
            return size() > MAXIMO_ENTRADAS;
        }
    };

    @Autowired
    public FotosEspeciesService(FotosCientificoHttp cientifico) {
        this(cientifico, Clock.systemUTC());
    }

    FotosEspeciesService(FotosCientificoHttp cientifico, Clock reloj) {
        this.cientifico = cientifico;
        this.reloj = reloj;
    }

    public Optional<byte[]> foto(String nombreCientifico) {
        String clave = nombreCientifico.trim().toLowerCase(Locale.ROOT);
        Instant ahora = reloj.instant();
        synchronized (cache) {
            Entrada e = cache.get(clave);
            if (e != null && e.vence().isAfter(ahora)) {
                return Optional.ofNullable(e.bytes());
            }
        }
        Optional<byte[]> foto = cientifico.fotoPublica(nombreCientifico.trim());
        synchronized (cache) {
            cache.put(clave, new Entrada(foto.orElse(null),
                    ahora.plus(foto.isPresent() ? VIGENCIA_FOTO : VIGENCIA_SIN_FOTO)));
        }
        return foto;
    }

    private record Entrada(byte[] bytes, Instant vence) {
    }
}
