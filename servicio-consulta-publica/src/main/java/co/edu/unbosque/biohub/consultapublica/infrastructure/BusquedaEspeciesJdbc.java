package co.edu.unbosque.biohub.consultapublica.infrastructure;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import co.edu.unbosque.biohub.consultapublica.service.CriterioBusquedaPublica;
import co.edu.unbosque.biohub.consultapublica.service.vista.EspecieResumenResponse;

/**
 * Catálogo público agrupado por especie (D65): los mismos filtros que la búsqueda de fichas, pero una fila por
 * {@code nombre_cientifico}. Dos consultas por página: la agrupada (conteos, departamentos, regiones) y la de los
 * registros más recientes de esas especies (nombre común, familia, estado y ficha que se abre). Así no se depende
 * de {@code ONLY_FULL_GROUP_BY} ni de funciones de ventana. Solo columnas públicas.
 */
@Repository
public class BusquedaEspeciesJdbc {

    private static final String SEPARADOR = "|";
    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");

    private final JdbcClient jdbc;

    public BusquedaEspeciesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long contar(CriterioBusquedaPublica criterio) {
        List<Object> parametros = new ArrayList<>();
        String where = BusquedaFichasJdbc.construirWhere(criterio, parametros);
        return jdbc.sql("SELECT COUNT(DISTINCT nombre_cientifico) FROM ficha_publica" + where)
                .params(parametros)
                .query(Long.class)
                .single();
    }

    public List<EspecieResumenResponse> buscar(CriterioBusquedaPublica criterio) {
        List<Object> parametros = new ArrayList<>();
        String where = BusquedaFichasJdbc.construirWhere(criterio, parametros);
        parametros.add(criterio.size());
        parametros.add(criterio.offset());
        List<Agrupado> grupos = jdbc.sql("SELECT nombre_cientifico, COUNT(*) AS registros, MAX(fecha_recoleccion) AS ultima,"
                        + " GROUP_CONCAT(DISTINCT departamento_nombre ORDER BY departamento_nombre SEPARATOR '|') AS departamentos,"
                        + " GROUP_CONCAT(DISTINCT region_nombre ORDER BY region_nombre SEPARATOR '|') AS regiones"
                        + " FROM ficha_publica" + where
                        + " GROUP BY nombre_cientifico ORDER BY nombre_cientifico LIMIT ? OFFSET ?")
                .params(parametros)
                .query((rs, n) -> new Agrupado(
                        rs.getString("nombre_cientifico"),
                        rs.getLong("registros"),
                        rs.getObject("ultima", LocalDate.class),
                        partir(rs.getString("departamentos")),
                        partir(rs.getString("regiones"))))
                .list();
        if (grupos.isEmpty()) {
            return List.of();
        }

        List<String> nombres = grupos.stream().map(Agrupado::nombre).toList();
        Map<String, Reciente> recientes = recientes(nombres);
        // ARQ-R-5: en modo degradado se omiten las fotos (una consulta menos por página); el portal usa su ilustración
        Map<String, UUID> fotos = criterio.modoDegradado() ? Map.of() : fotoIdPorEspecie(nombres);
        return grupos.stream().map(g -> {
            Reciente r = recientes.get(g.nombre());
            return new EspecieResumenResponse(g.nombre(), r == null ? null : r.nombreComun(),
                    r == null ? null : r.familia(), r == null ? null : r.estado(), g.registros(),
                    g.departamentos(), g.regiones(), g.ultima(), r == null ? null : r.fichaId(),
                    fotos.get(claveNombre(g.nombre())));
        }).toList();
    }

    /** Familias con registros públicos, en orden; con tope para que el desplegable nunca sea enorme. */
    public List<String> familias(int tope) {
        return jdbc.sql("SELECT DISTINCT familia FROM ficha_publica ORDER BY familia LIMIT ?")
                .param(tope)
                .query(String.class)
                .list();
    }

    /**
     * La foto pública más reciente de cada especie (D66): une {@code foto_publica} con las fichas por
     * {@code hallazgo_id}; la especie de una foto es la de su ficha, así que una reclasificación no exige otro evento.
     * No lee los bytes. La clave del mapa es el nombre sin mayúsculas ni tildes (la colación de la tabla los iguala).
     */
    private Map<String, UUID> fotoIdPorEspecie(List<String> nombres) {
        String marcadores = String.join(",", Collections.nCopies(nombres.size(), "?"));
        Map<String, UUID> porNombre = new LinkedHashMap<>();
        jdbc.sql("SELECT f.nombre_cientifico, fp.evidencia_id"
                        + " FROM foto_publica fp JOIN ficha_publica f ON f.hallazgo_id = fp.hallazgo_id"
                        + " WHERE fp.publica = TRUE AND f.nombre_cientifico IN (" + marcadores + ")"
                        + " ORDER BY fp.cambio_en DESC, fp.evidencia_id")
                .params(new ArrayList<Object>(nombres))
                .query((rs, n) -> Map.entry(claveNombre(rs.getString("nombre_cientifico")),
                        UuidBinario.aUuid(rs.getBytes("evidencia_id"))))
                .list()
                .forEach(e -> porNombre.putIfAbsent(e.getKey(), e.getValue()));
        return porNombre;
    }

    private static String claveNombre(String nombre) {
        return MARCAS.matcher(Normalizer.normalize(nombre, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT).trim();
    }

    /** El registro más reciente de cada especie: el primero por fecha de recolección y luego por actualización. */
    private Map<String, Reciente> recientes(List<String> nombres) {
        String marcadores = String.join(",", Collections.nCopies(nombres.size(), "?"));
        Map<String, Reciente> porNombre = new LinkedHashMap<>();
        jdbc.sql("SELECT hallazgo_id, nombre_cientifico, nombre_comun, familia, estado_conservacion"
                        + " FROM ficha_publica WHERE nombre_cientifico IN (" + marcadores + ")"
                        + " ORDER BY fecha_recoleccion DESC, actualizado_en DESC")
                .params(new ArrayList<Object>(nombres))
                .query((rs, n) -> new Reciente(
                        rs.getString("nombre_cientifico"),
                        rs.getString("nombre_comun"),
                        rs.getString("familia"),
                        rs.getString("estado_conservacion"),
                        UuidBinario.aUuid(rs.getBytes("hallazgo_id"))))
                .list()
                .forEach(r -> porNombre.putIfAbsent(r.nombre(), r));
        return porNombre;
    }

    private static List<String> partir(String concatenado) {
        if (concatenado == null || concatenado.isBlank()) {
            return List.of();
        }
        return Arrays.stream(concatenado.split("\\" + SEPARADOR)).filter(s -> !s.isBlank()).toList();
    }

    private record Agrupado(String nombre, long registros, LocalDate ultima, List<String> departamentos,
                            List<String> regiones) {
    }

    private record Reciente(String nombre, String nombreComun, String familia, String estado, UUID fichaId) {
    }
}
