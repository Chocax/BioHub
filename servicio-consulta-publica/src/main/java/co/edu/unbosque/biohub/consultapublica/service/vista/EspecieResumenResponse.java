package co.edu.unbosque.biohub.consultapublica.service.vista;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Una especie del catálogo público, con todos sus registros agrupados (D65). Solo datos públicos: nunca
 * coordenadas ni el investigador. {@code fichaId} es el registro más reciente, el que abre la ficha.
 * {@code fotoId} es la foto pública más reciente de la especie (D66) o {@code null}: el portal solo pide
 * {@code /api/publica/especies/fotos/{fotoId}} cuando no es {@code null}. En modo degradado va siempre {@code null}.
 */
public record EspecieResumenResponse(String nombreCientifico, String nombreComun, String familia,
                                     String estadoConservacion, long registros, List<String> departamentos,
                                     List<String> regiones, LocalDate ultimaRecoleccion, UUID fichaId,
                                     UUID fotoId) {
}
