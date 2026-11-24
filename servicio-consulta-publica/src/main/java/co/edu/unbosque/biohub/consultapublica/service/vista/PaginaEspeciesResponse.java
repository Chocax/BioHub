package co.edu.unbosque.biohub.consultapublica.service.vista;

import java.util.List;

/** Respuesta paginada del catálogo de especies (contrato 04, §4.4). Mismos campos de página que las fichas. */
public record PaginaEspeciesResponse(List<EspecieResumenResponse> content, int page, int size, long total,
                                     int totalPaginas, boolean modoDegradado, List<String> filtrosIgnorados) {
}
