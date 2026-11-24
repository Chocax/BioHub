package co.edu.unbosque.biohub.consultapublica.service.vista;

import java.util.List;

/** Familias con al menos un registro público, en orden alfabético: alimenta el filtro del portal (D65). */
public record FamiliasResponse(List<String> familias) {
}
