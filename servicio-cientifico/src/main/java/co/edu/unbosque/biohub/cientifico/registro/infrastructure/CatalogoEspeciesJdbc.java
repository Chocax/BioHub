package co.edu.unbosque.biohub.cientifico.registro.infrastructure;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import co.edu.unbosque.biohub.cientifico.registro.domain.CatalogoEspecies;

/**
 * {@link CatalogoEspecies} con SQL: agrupar por especie y unir hallazgos con fichas no se expresa bien en JPQL.
 * Parámetros siempre enlazados. Una página son cuatro consultas cortas (agrupada, registro más reciente, ficha y
 * foto) en vez de una sola con funciones de ventana, para no depender de {@code ONLY_FULL_GROUP_BY}.
 */
@Repository
public class CatalogoEspeciesJdbc implements CatalogoEspecies {

    private final JdbcClient jdbc;

    public CatalogoEspeciesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long contar(String texto, String familia) {
        List<Object> parametros = new ArrayList<>();
        String origen = origen(texto, familia, parametros);
        return jdbc.sql("SELECT COUNT(*) FROM (SELECT especie FROM (" + origen + ") x GROUP BY especie) y")
                .params(parametros)
                .query(Long.class)
                .single();
    }

    @Override
    public List<Especie> buscar(String texto, String familia, int page, int size) {
        List<Object> parametros = new ArrayList<>();
        String origen = origen(texto, familia, parametros);
        parametros.add(size);
        parametros.add((long) page * size);
        record Grupo(String nombre, long registros, LocalDate ultima, List<String> departamentos) {
        }
        List<Grupo> grupos = jdbc.sql("SELECT especie, SUM(reg) AS registros, MAX(f) AS ultima,"
                        + " GROUP_CONCAT(DISTINCT d ORDER BY d SEPARATOR '|') AS departamentos"
                        + " FROM (" + origen + ") x GROUP BY especie ORDER BY especie LIMIT ? OFFSET ?")
                .params(parametros)
                .query((rs, n) -> new Grupo(rs.getString("especie"), rs.getLong("registros"),
                        rs.getObject("ultima", LocalDate.class), partir(rs.getString("departamentos"))))
                .list();
        if (grupos.isEmpty()) {
            return List.of();
        }
        List<Object> nombres = new ArrayList<>(grupos.stream().map(Grupo::nombre).toList());
        String enLista = String.join(",", Collections.nCopies(nombres.size(), "?"));

        Map<String, String[]> recientes = new HashMap<>();
        jdbc.sql("SELECT nombre_cientifico, nombre_comun, familia, estado_conservacion, categoria_uicn FROM hallazgo"
                        + " WHERE anulado_en IS NULL AND nombre_cientifico IN (" + enLista + ")"
                        + " ORDER BY fecha_recoleccion DESC, creado_en DESC")
                .params(nombres)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)})
                .list()
                .forEach(f -> recientes.putIfAbsent(clave(f[0]), f));

        Map<String, int[]> fichas = new HashMap<>();
        jdbc.sql("SELECT nombre_cientifico, version, JSON_LENGTH(atributos) FROM ficha_plantilla"
                        + " WHERE nombre_cientifico IN (" + enLista + ") ORDER BY version DESC")
                .params(nombres)
                .query((rs, n) -> new Object[] {rs.getString(1), new int[] {rs.getInt(2), rs.getInt(3)}})
                .list()
                .forEach(f -> fichas.putIfAbsent(clave((String) f[0]), (int[]) f[1]));

        Map<String, Foto> fotos = new HashMap<>();
        jdbc.sql("SELECT h.nombre_cientifico, e.hallazgo_id, e.id, e.publica FROM hallazgo_evidencia e"
                        + " JOIN hallazgo h ON h.id = e.hallazgo_id"
                        + " WHERE h.anulado_en IS NULL AND e.tipo = 'FOTOGRAFIA' AND h.nombre_cientifico IN (" + enLista + ")"
                        + " ORDER BY e.publica DESC, e.creado_en DESC")
                .params(nombres)
                .query((rs, n) -> new Object[] {rs.getString(1),
                        new Foto(uuid(rs.getBytes(2)), uuid(rs.getBytes(3)), rs.getBoolean(4))})
                .list()
                .forEach(f -> fotos.putIfAbsent(clave((String) f[0]), (Foto) f[1]));

        return grupos.stream().map(g -> {
            String[] r = recientes.get(clave(g.nombre()));
            int[] ficha = fichas.get(clave(g.nombre()));
            return new Especie(g.nombre(), r == null ? null : r[1], r == null ? null : r[2], r == null ? null : r[3],
                    r == null ? null : r[4], g.registros(), g.departamentos(), g.ultima(),
                    ficha == null ? null : ficha[0], ficha == null ? null : ficha[1], fotos.get(clave(g.nombre())));
        }).toList();
    }

    @Override
    public Sugerencias sugerencias(int tope) {
        List<EspecieConocida> especies = jdbc.sql("SELECT nombre_cientifico, MAX(nombre_comun), MAX(familia) FROM hallazgo"
                        + " WHERE anulado_en IS NULL GROUP BY nombre_cientifico ORDER BY nombre_cientifico LIMIT ?")
                .param(tope)
                .query((rs, n) -> new EspecieConocida(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
        List<String> familias = jdbc.sql("SELECT DISTINCT familia FROM hallazgo WHERE anulado_en IS NULL ORDER BY familia LIMIT ?")
                .param(tope).query(String.class).list();
        List<String> investigadores = jdbc.sql("SELECT DISTINCT investigador FROM hallazgo ORDER BY investigador LIMIT ?")
                .param(tope).query(String.class).list();
        return new Sugerencias(especies, familias, investigadores);
    }

    /**
     * Filas (especie, registro, fecha, departamento) de los hallazgos vigentes más una fila vacía por especie con
     * ficha: así aparece en la galería aunque todavía no tenga hallazgos. Con filtro de familia solo cuentan los
     * hallazgos (la ficha no guarda familia).
     */
    private static String origen(String texto, String familia, List<Object> parametros) {
        StringBuilder sql = new StringBuilder("SELECT nombre_cientifico AS especie, 1 AS reg, fecha_recoleccion AS f,"
                + " departamento AS d FROM hallazgo WHERE anulado_en IS NULL");
        if (texto != null) {
            sql.append(" AND (nombre_cientifico LIKE ? OR nombre_comun LIKE ?)");
            parametros.add(patron(texto));
            parametros.add(patron(texto));
        }
        if (familia != null) {
            sql.append(" AND familia = ?");
            parametros.add(familia);
            return sql.toString();
        }
        sql.append(" UNION ALL SELECT DISTINCT nombre_cientifico, 0, NULL, NULL FROM ficha_plantilla");
        if (texto != null) {
            sql.append(" WHERE nombre_cientifico LIKE ?");
            parametros.add(patron(texto));
        }
        return sql.toString();
    }

    /** Contiene el texto, con {@code \}, {@code %} y {@code _} escapados. */
    private static String patron(String texto) {
        return "%" + texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String clave(String nombre) {
        return nombre == null ? "" : nombre.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> partir(String concatenado) {
        if (concatenado == null || concatenado.isBlank()) {
            return List.of();
        }
        return Arrays.stream(concatenado.split("\\|")).filter(s -> !s.isBlank()).toList();
    }

    private static UUID uuid(byte[] b) {
        ByteBuffer buffer = ByteBuffer.wrap(b);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
