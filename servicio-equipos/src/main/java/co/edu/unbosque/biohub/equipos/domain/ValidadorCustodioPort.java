package co.edu.unbosque.biohub.equipos.domain;

import java.util.List;

public interface ValidadorCustodioPort {
    CustodioValidado validarCustodioActivo(String custodioId, String bearerToken);

    /** Rol que HU-04 c.1 exige al custodio: "un investigador registrado y activo". */
    String ROL_INVESTIGADOR = "investigador_campo";

    record CustodioValidado(String id, String usuario, boolean activo, List<String> roles) {

        public CustodioValidado {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public boolean esInvestigador() {
            return roles.contains(ROL_INVESTIGADOR);
        }
    }
}
