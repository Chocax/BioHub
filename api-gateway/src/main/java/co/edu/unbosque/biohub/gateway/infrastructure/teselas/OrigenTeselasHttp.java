package co.edu.unbosque.biohub.gateway.infrastructure.teselas;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import co.edu.unbosque.biohub.gateway.config.PropiedadesTeselas;
import co.edu.unbosque.biohub.gateway.domain.ImagenTesela;
import co.edu.unbosque.biohub.gateway.domain.OrigenTeselas;
import co.edu.unbosque.biohub.gateway.domain.Tesela;
import co.edu.unbosque.biohub.gateway.service.TeselaNoDisponibleException;

/**
 * Descarga teselas por HTTP (Adapter del puerto {@link OrigenTeselas}).
 *
 * <p>La petición se arma desde cero: solo lleva {@code User-Agent} y {@code Accept}. Nada del navegador viaja al
 * origen (ni IP, ni token, ni cookies, ni Referer), y eso es lo que protege al científico (SEC-J-04). El destino sale
 * solo de la configuración y no se siguen redirecciones, así que el proxy no puede usarse para llegar a otro
 * servidor (SSRF).
 */
@Component
public class OrigenTeselasHttp implements OrigenTeselas {

    private final RestClient cliente;
    private final String plantilla;
    private final String agenteUsuario;
    private final long tamanoMaximo;

    public OrigenTeselasHttp(PropiedadesTeselas propiedades) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(propiedades.timeoutConexion())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
        fabrica.setReadTimeout(propiedades.timeoutLectura());
        this.cliente = RestClient.builder().requestFactory(fabrica).build();
        this.plantilla = propiedades.urlOrigen();
        this.agenteUsuario = propiedades.agenteUsuario();
        this.tamanoMaximo = propiedades.tamanoMaximo().toBytes();
    }

    @Override
    public ImagenTesela descargar(Tesela tesela) {
        return cliente.get()
                .uri(plantilla, Map.of("z", tesela.z(), "x", tesela.x(), "y", tesela.y()))
                .headers(cabeceras -> {
                    cabeceras.clear();
                    cabeceras.set(HttpHeaders.USER_AGENT, agenteUsuario);
                    cabeceras.setAccept(List.of(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG));
                })
                .exchange((peticion, respuesta) -> {
                    if (respuesta.getStatusCode().value() != 200) {
                        throw new TeselaNoDisponibleException("El servidor de teselas respondió "
                                + respuesta.getStatusCode().value());
                    }
                    MediaType tipo = respuesta.getHeaders().getContentType();
                    if (tipo == null || !"image".equals(tipo.getType())) {
                        throw new TeselaNoDisponibleException("El servidor de teselas no devolvió una imagen");
                    }
                    return new ImagenTesela(leerAcotado(respuesta.getBody()), tipo.getType() + "/" + tipo.getSubtype());
                });
    }

    /**
     * Nunca carga en memoria más de tamanoMaximo + 1 bytes. Un error de lectura sale como IOException, que
     * RestClient convierte en ResourceAccessException: cuenta como caída para el circuit breaker.
     */
    private byte[] leerAcotado(InputStream cuerpo) throws IOException {
        try (cuerpo) {
            byte[] datos = cuerpo.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, tamanoMaximo + 1));
            if (datos.length > tamanoMaximo) {
                throw new TeselaNoDisponibleException("La tesela supera el tamaño máximo");
            }
            if (datos.length == 0) {
                throw new TeselaNoDisponibleException("La tesela llegó vacía");
            }
            return datos;
        }
    }
}
