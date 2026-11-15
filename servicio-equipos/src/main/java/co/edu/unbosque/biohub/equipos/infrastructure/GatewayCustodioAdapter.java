package co.edu.unbosque.biohub.equipos.infrastructure;

import co.edu.unbosque.biohub.equipos.domain.ValidadorCustodioPort;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GatewayCustodioAdapter implements ValidadorCustodioPort {

    private final RestClient restClient;

    public GatewayCustodioAdapter(@Value("${biohub.gateway.url:http://localhost:8080}") String gatewayUrl) {
        this.restClient = RestClient.builder()
            .baseUrl(gatewayUrl)
            .build();
    }

    @Override
    public CustodioValidado validarCustodioActivo(String custodioId, String bearerToken) {
        try {
            UsuarioGatewayDto dto = restClient.get()
                .uri("/api/gateway/usuarios/{id}", custodioId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .body(UsuarioGatewayDto.class);

            if (dto == null) {
                throw new CustodioInvalidoException("No se pudo verificar el custodio en Keycloak.");
            }
            return new CustodioValidado(dto.id(), dto.usuario(), dto.activo());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new CustodioInvalidoException("El custodio especificado no existe.");
            }
            throw new CustodioInvalidoException("No fue posible validar el custodio en el gateway.");
        } catch (RestClientException e) {
            throw new GatewayNoDisponibleException("El servicio de identidad no está disponible en este momento.");
        }
    }

    private record UsuarioGatewayDto(String id, String usuario, boolean activo, List<String> roles) {}

    public static class CustodioInvalidoException extends RuntimeException {
        public CustodioInvalidoException(String message) { super(message); }
    }

    public static class GatewayNoDisponibleException extends RuntimeException {
        public GatewayNoDisponibleException(String message) { super(message); }
    }
}
