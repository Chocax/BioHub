package co.edu.unbosque.biohub.equipos.domain;

public interface ValidadorCustodioPort {
    CustodioValidado validarCustodioActivo(String custodioId, String bearerToken);

    record CustodioValidado(String id, String usuario, boolean activo) {}
}
