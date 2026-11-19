package co.edu.unbosque.biohub.consultapublica.infrastructure;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Pide al Servicio Científico la foto pública de una especie (D65). Ese endpoint no exige token pero solo
 * escucha en la red interna, y solo entrega fotos que un investigador marcó como públicas, ya sin metadatos
 * y reducidas. Si el científico no responde, el portal sigue funcionando: se muestra la ilustración.
 */
@Component
public class FotosCientificoHttp {

    private static final Logger log = LoggerFactory.getLogger(FotosCientificoHttp.class);

    private final RestClient cliente;

    public FotosCientificoHttp(@Value("${biohub.publica.cientifico-url:${SERVICIO_CIENTIFICO_URL:http://localhost:8081}}") String url) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(2));
        fabrica.setReadTimeout(Duration.ofSeconds(5));
        this.cliente = RestClient.builder().baseUrl(url).requestFactory(fabrica).build();
    }

    /** La foto en JPEG, vacío si la especie no tiene foto pública o si el científico no respondió. */
    public Optional<byte[]> fotoPublica(String nombreCientifico) {
        try {
            byte[] cuerpo = cliente.get()
                    .uri(u -> u.path("/api/cientifico/publico/especies/foto").queryParam("nombre", nombreCientifico).build())
                    .retrieve()
                    .body(byte[].class);
            return Optional.ofNullable(cuerpo).filter(b -> b.length > 0);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                log.warn("El científico respondió {} al pedir una foto pública", e.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("No se pudo pedir la foto pública al científico: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
