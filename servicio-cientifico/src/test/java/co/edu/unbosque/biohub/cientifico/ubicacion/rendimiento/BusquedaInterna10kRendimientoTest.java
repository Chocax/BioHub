package co.edu.unbosque.biohub.cientifico.ubicacion.rendimiento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unbosque.biohub.cientifico.ubicacion.BaseUbicacionMySqlTest;
import co.edu.unbosque.biohub.cientifico.ubicacion.domain.CifradorCoordenadas;
import co.edu.unbosque.biohub.cientifico.ubicacion.domain.Coordenadas;
import co.edu.unbosque.biohub.cientifico.ubicacion.domain.CoordenadasCifradas;
import co.edu.unbosque.biohub.cientifico.ubicacion.service.ServicioDescifrado;

/**
 * Rendimiento de la busqueda interna con 10.000 hallazgos (HU-13 criterio 3; casos CG-08, HB-23 y DB-07 del plan 06).
 *
 * <p><b>Desactivada por defecto</b>: solo corre con {@code -Drendimiento=true}, asi el {@code verify} normal y la CI
 * la reportan como omitida. Comando (desde la raiz del repositorio, Docker accesible para Testcontainers):
 * <pre>
 * mvn -B -ntp -pl servicio-cientifico -am verify -Drendimiento=true \
 *     -Dtest=BusquedaInterna10kRendimientoTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Con Docker detras de un grupo: {@code sg docker -c "mvn ..."}. El resumen queda en la salida estandar y en
 * {@code servicio-cientifico/target/rendimiento/busqueda-10k-<fecha>.txt}.
 *
 * <p><b>Que mide.</b> MySQL 8.4 real (Testcontainers) con las migraciones Flyway reales, el contexto Spring de
 * {@link BaseUbicacionMySqlTest} y MockMvc con la cadena de seguridad real (el JWT se simula con el mock de
 * {@code JwtDecoder} de la base). Siembra 10.000 hallazgos (20 % {@code EN_PELIGRO}) con las coordenadas cifradas por el
 * {@link CifradorCoordenadas} real (AES-256-GCM) y las inserta en lotes por JDBC; el tiempo de siembra se reporta aparte.
 * Por cada caso: 10 peticiones de calentamiento descartadas y 100 medidas con {@link System#nanoTime()} (solo
 * {@code perform} y la lectura del cuerpo; el analisis del JSON y las aserciones quedan fuera del cronometro). Cada
 * peticion lleva 2 filtros combinados, que rotan entre 4 combinaciones, todas con mas de 100 resultados.
 * El p95 se calcula por el metodo del rango mas cercano: el elemento de posicion {@code ceil(0.95 n)} de los tiempos ordenados.
 *
 * <p><b>Limite de lo que mide.</b> No incluye red, gateway (autenticacion y limites de tasa), validacion criptografica
 * real del JWT contra Keycloak, TLS, el navegador, ni la contencion de usuarios concurrentes: es el servicio en
 * proceso, secuencial. Los tiempos de extremo a extremo seran mayores en esas partes.
 *
 * <p>La base hereda un {@code @BeforeEach} que vacia la tabla, por eso todo el escenario va en un unico {@code @Test}.
 */
@Tag("rendimiento")
@EnabledIfSystemProperty(named = "rendimiento", matches = "true")
class BusquedaInterna10kRendimientoTest extends BaseUbicacionMySqlTest {

    private static final String URL = "/api/cientifico/ubicacion/hallazgos";
    private static final int TOTAL = 10_000;
    private static final int PORCENTAJE_EN_PELIGRO = 20;
    private static final int CALENTAMIENTO = 10;
    private static final int MEDIDAS = 100;
    private static final long UMBRAL_P95_MS = 3_000;

    private static final String[] GENEROS = {"Atelopus", "Rhinella", "Dendrobates", "Oophaga", "Pristimantis", "Boana",
        "Leptodactylus", "Hyloxalus"};
    private static final String[] EPITETOS = {"alpha", "beta", "gamma", "delta", "epsilon"};
    // Codigos DANE de 8 departamentos del catalogo sembrado por Flyway
    private static final String[] DEPARTAMENTOS = {"05", "11", "15", "25", "27", "50", "68", "76"};
    private static final int INVESTIGADORES = 20;
    private static final LocalDate FECHA_INICIO = LocalDate.of(2024, 1, 1);
    private static final int DIAS = 1_030; // hasta octubre de 2026

    @Autowired
    private CifradorCoordenadas cifrador;
    @Autowired
    private ObjectMapper json;
    @MockitoSpyBean
    private ServicioDescifrado servicioDescifrado;

    /** Una combinacion de 2 filtros; {@code params} en el orden en que el repositorio los enlaza. */
    private record Combo(String nombre, String query, List<Object> params, String condicionSql) {
    }

    private record Resultado(String caso, int size, boolean conCoordenadas, long[] msOrdenados, double maxCalentamientoMs,
                             int descifradosMin, int descifradosMax, double descifradosMedia, long totalMin,
                             long totalMax) {
        double p(double q) {
            int n = msOrdenados.length;
            int idx = (int) Math.ceil(q * n) - 1;
            return msOrdenados[Math.max(0, Math.min(n - 1, idx))] / 1_000.0; // los tiempos se guardan en microsegundos
        }

        double max() {
            return msOrdenados[msOrdenados.length - 1] / 1_000.0;
        }
    }

    private final StringBuilder informe = new StringBuilder();

    @AfterEach
    void vaciar() {
        jdbc.update("DELETE FROM ubicacion_hallazgo"); // la BD de Testcontainers la comparten otras clases
    }

    @Test
    void busquedaInternaConDiezMilHallazgos() throws Exception {
        List<Combo> combos = combinaciones();
        String altoRango = bearer("cientifico1", "cientifico_alto_rango");
        String investigador = bearer("investigador1", "investigador_campo");

        // --- siembra ---
        List<UUID> idsMuestra = new ArrayList<>();
        List<CoordenadasCifradas> cifradosMuestra = new ArrayList<>();
        long inicioSiembra = System.nanoTime();
        int enPeligro = sembrar10k(idsMuestra, cifradosMuestra);
        long msSiembra = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioSiembra);
        long inicioAnalyze = System.nanoTime();
        jdbc.execute("ANALYZE TABLE ubicacion_hallazgo");
        long msAnalyze = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioAnalyze);

        Long filas = jdbc.queryForObject("SELECT COUNT(*) FROM ubicacion_hallazgo", Long.class);
        Long peligro = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ubicacion_hallazgo WHERE estado_conservacion = 'EN_PELIGRO'", Long.class);
        Long conCifrado = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ubicacion_hallazgo WHERE coord_cifrada IS NOT NULL AND coord_nonce IS NOT NULL", Long.class);
        assertThat(filas).isEqualTo(TOTAL);
        assertThat(peligro).isEqualTo(TOTAL * PORCENTAJE_EN_PELIGRO / 100L).isEqualTo(enPeligro);
        assertThat(conCifrado).isEqualTo(TOTAL);
        // Las coordenadas en la BD no estan en claro: ningun registro contiene los 16 bytes de dos doubles en claro
        assertThat(jdbc.queryForObject("SELECT MAX(LENGTH(coord_cifrada)) FROM ubicacion_hallazgo", Integer.class)).isEqualTo(32);

        // --- coste del descifrado aislado (AES-GCM real, 100 filas x 50 pasadas) ---
        double usPorDescifrado = medirDescifradoAislado(idsMuestra, cifradosMuestra);

        // --- consultas del plan (DB-07) ---
        String explain = "--- Estadisticas tal como quedan tras ANALYZE TABLE ubicacion_hallazgo (el catalogo no se analiza) ---\n"
                + explicar(combos, altoRango);

        // --- medicion ---
        List<Resultado> resultados = new ArrayList<>();
        for (int size : new int[] {20, 50, 100}) {
            resultados.add(medir("cientifico1 (alto rango) size=" + size, altoRango, true, size, combos, 0));
        }
        for (int size : new int[] {20, 50, 100}) {
            resultados.add(medir("investigador1 (sin coordenadas) size=" + size, investigador, false, size, combos, 0));
        }

        // --- peor caso informativo (no se afirma): 2 filtros que abarcan los 10.000 y pagina profunda ---
        Combo todo = new Combo("peor caso: desde/hasta que cubren todo", "?desde=2024-01-01&hasta=2026-12-31",
                List.of(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 12, 31)),
                "h.fecha_recoleccion >= ? and h.fecha_recoleccion <= ?");
        List<Resultado> peorCaso = new ArrayList<>();
        peorCaso.add(medirInformativo("PEOR CASO cientifico1 size=100 page=0 (10.000 coincidencias)", altoRango, true, 100, todo, 0));
        peorCaso.add(medirInformativo("PEOR CASO cientifico1 size=100 page=99 (offset 9.900)", altoRango, true, 100, todo, 99));
        peorCaso.add(medirInformativo("PEOR CASO investigador1 size=100 page=99 (offset 9.900)", investigador, false, 100, todo, 99));

        // --- DB-07 segunda pasada (diagnostico): con estadisticas frescas tambien de las tablas del catalogo ---
        jdbc.execute("ANALYZE TABLE ubicacion_departamento, ubicacion_region");
        explain += "\n--- Tras ANALYZE TABLE ubicacion_departamento, ubicacion_region (diagnostico; la medicion se hizo ANTES) ---\n"
                + explicar(combos, altoRango);

        // --- informe ---
        escribirInforme(msSiembra, msAnalyze, usPorDescifrado, explain, resultados, peorCaso, combos);

        // --- aserciones de umbral (despues de imprimir, para que el informe exista aunque falle) ---
        for (Resultado r : resultados) {
            assertThat(r.p(0.95)).as("p95 (ms) de " + r.caso()).isLessThan((double) UMBRAL_P95_MS);
        }
    }

    // ------------------------------------------------------------------ siembra

    private int sembrar10k(List<UUID> idsMuestra, List<CoordenadasCifradas> cifradosMuestra) throws SQLException {
        Random azar = new Random(20261115L); // reproducible
        String sql = "INSERT INTO ubicacion_hallazgo (hallazgo_id, version, departamento_codigo, nombre_cientifico,"
                + " fecha_recoleccion, investigador, estado_conservacion, coord_cifrada, coord_nonce, clave_version,"
                + " cifrado_en) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "rewriteBatchedStatements=true";
        int enPeligro = 0;
        // Se inserta con una conexion propia para poder usar rewriteBatchedStatements (la del contexto no lo trae)
        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(url, MYSQL.getUsername(), MYSQL.getPassword(), true)) {
            ds.setAutoCommit(false);
            java.sql.Connection c = ds.getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Timestamp ahora = Timestamp.from(Instant.now());
                for (int i = 0; i < TOTAL; i++) {
                    UUID id = UUID.randomUUID();
                    boolean peligro = i % 100 < PORCENTAJE_EN_PELIGRO; // 20 % exacto; el resto de campos es aleatorio
                    if (peligro) {
                        enPeligro++;
                    }
                    String estado = peligro ? "EN_PELIGRO" : (azar.nextBoolean() ? "NO_AMENAZADA" : "SIN_EVALUAR");
                    String especie = GENEROS[azar.nextInt(GENEROS.length)] + " " + EPITETOS[azar.nextInt(EPITETOS.length)];
                    LocalDate fecha = FECHA_INICIO.plusDays(azar.nextInt(DIAS));
                    String invest = "investigador" + (1 + azar.nextInt(INVESTIGADORES));
                    String depto = DEPARTAMENTOS[azar.nextInt(DEPARTAMENTOS.length)];
                    Coordenadas coord = new Coordenadas(-4 + azar.nextDouble() * 16, -79 + azar.nextDouble() * 12);
                    CoordenadasCifradas cif = cifrador.cifrar(id, coord); // componente real de cifrado

                    ps.setBytes(1, bytes(id));
                    ps.setInt(2, 1);
                    ps.setString(3, depto);
                    ps.setString(4, especie);
                    ps.setObject(5, fecha);
                    ps.setString(6, invest);
                    ps.setString(7, estado);
                    ps.setBytes(8, cif.textoCifrado());
                    ps.setBytes(9, cif.nonce());
                    ps.setString(10, cif.claveVersion());
                    ps.setTimestamp(11, ahora);
                    ps.addBatch();
                    if (idsMuestra.size() < 100) {
                        idsMuestra.add(id);
                        cifradosMuestra.add(cif);
                    }
                    if ((i + 1) % 1_000 == 0) {
                        ps.executeBatch();
                        c.commit();
                    }
                }
                ps.executeBatch();
                c.commit();
            }
        }
        return enPeligro;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private double medirDescifradoAislado(List<UUID> ids, List<CoordenadasCifradas> cifrados) {
        for (int i = 0; i < 100; i++) { // calentamiento
            cifrador.descifrar(ids.get(i), cifrados.get(i));
        }
        long t0 = System.nanoTime();
        int n = 0;
        for (int pasada = 0; pasada < 50; pasada++) {
            for (int i = 0; i < ids.size(); i++) {
                cifrador.descifrar(ids.get(i), cifrados.get(i));
                n++;
            }
        }
        return (System.nanoTime() - t0) / 1_000.0 / n;
    }

    // ------------------------------------------------------------------ combinaciones de filtros

    private List<Combo> combinaciones() {
        return List.of(
                new Combo("especie=Atelopus + departamento=15", "?especie=Atelopus&departamento=15",
                        List.of("Atelopus%", "15"), "h.nombre_cientifico like ? escape '!' and h.departamento_codigo = ?"),
                new Combo("departamento=25 + desde/hasta 2025", "?departamento=25&desde=2025-01-01&hasta=2025-12-31",
                        List.of(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "25"),
                        "h.fecha_recoleccion >= ? and h.fecha_recoleccion <= ? and h.departamento_codigo = ?"),
                new Combo("investigador=investigador3 + desde=2025-01-01", "?investigador=investigador3&desde=2025-01-01",
                        List.of(LocalDate.of(2025, 1, 1), "investigador3"),
                        "h.fecha_recoleccion >= ? and h.investigador = ?"),
                new Combo("especie=Dendrobates + desde/hasta 2025-06..2026-06", "?especie=Dendrobates&desde=2025-06-01&hasta=2026-06-30",
                        List.of("Dendrobates%", LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 30)),
                        "h.nombre_cientifico like ? escape '!' and h.fecha_recoleccion >= ? and h.fecha_recoleccion <= ?"));
    }

    // ------------------------------------------------------------------ medicion

    private Resultado medir(String caso, String auth, boolean altoRango, int size, List<Combo> combos, int pagina)
            throws Exception {
        return medir(caso, auth, altoRango, size, combos, pagina, true);
    }

    private Resultado medirInformativo(String caso, String auth, boolean altoRango, int size, Combo combo, int pagina)
            throws Exception {
        return medir(caso, auth, altoRango, size, List.of(combo), pagina, false);
    }

    private Resultado medir(String caso, String auth, boolean altoRango, int size, List<Combo> combos, int pagina,
                            boolean exigirPaginaLlena) throws Exception {
        double maxCalentamiento = 0;
        for (int i = 0; i < CALENTAMIENTO; i++) {
            long t = ejecutar(combos.get(i % combos.size()), auth, size, pagina);
            maxCalentamiento = Math.max(maxCalentamiento, t / 1_000_000.0);
        }
        Mockito.clearInvocations(servicioDescifrado);

        long[] tiempos = new long[MEDIDAS];
        int dMin = Integer.MAX_VALUE;
        int dMax = 0;
        long dSuma = 0;
        long totMin = Long.MAX_VALUE;
        long totMax = 0;
        for (int i = 0; i < MEDIDAS; i++) {
            Combo combo = combos.get(i % combos.size());
            URI uri = URI.create(URL + combo.query() + "&size=" + size + "&page=" + pagina);
            long t0 = System.nanoTime();
            MvcResult r = mvc.perform(get(uri).header("Authorization", auth)).andReturn();
            String cuerpo = r.getResponse().getContentAsString();
            long dt = System.nanoTime() - t0;
            tiempos[i] = TimeUnit.NANOSECONDS.toMicros(dt);

            // --- aserciones (fuera del cronometro) ---
            assertThat(r.getResponse().getStatus()).as("estado HTTP de " + caso + " / " + combo.nombre()).isEqualTo(200);
            JsonNode raiz = json.readTree(cuerpo);
            JsonNode contenido = raiz.get("content");
            assertThat(contenido.size()).as("respuesta vacia en " + caso + " / " + combo.nombre()).isPositive();
            if (exigirPaginaLlena) {
                assertThat(contenido.size()).as("pagina llena en " + caso + " / " + combo.nombre()).isEqualTo(size);
            }
            assertThat(raiz.get("omitidos").asInt()).isZero();
            totMin = Math.min(totMin, raiz.get("total").asLong());
            totMax = Math.max(totMax, raiz.get("total").asLong());
            long descifrados = Mockito.mockingDetails(servicioDescifrado).getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("descifrar")).count();
            Mockito.clearInvocations(servicioDescifrado);
            dMin = (int) Math.min(dMin, descifrados);
            dMax = (int) Math.max(dMax, descifrados);
            dSuma += descifrados;
            if (altoRango) {
                assertThat(descifrados).as("HB-23: descifrados <= size en " + caso).isLessThanOrEqualTo(size);
                assertThat(descifrados).as("descifrados de la pagina en " + caso).isEqualTo(contenido.size());
                for (JsonNode h : contenido) {
                    assertThat(h.get("estadoCoordenadas").asText()).isEqualTo("VISIBLES");
                    assertThat(h.get("latitud").isNumber()).isTrue();
                    assertThat(h.get("longitud").isNumber()).isTrue();
                }
            } else {
                assertThat(descifrados).as("HB-23: el investigador no descifra nada en " + caso).isZero();
                assertThat(cuerpo).doesNotContain("latitud").doesNotContain("longitud");
                for (JsonNode h : contenido) {
                    assertThat(h.get("estadoCoordenadas").asText()).isEqualTo("PROTEGIDAS");
                }
            }
        }
        long[] ordenados = tiempos.clone();
        Arrays.sort(ordenados);
        return new Resultado(caso, size, altoRango, ordenados, maxCalentamiento, dMin, dMax, dSuma / (double) MEDIDAS,
                totMin, totMax);
    }

    private long ejecutar(Combo combo, String auth, int size, int pagina) throws Exception {
        URI uri = URI.create(URL + combo.query() + "&size=" + size + "&page=" + pagina);
        long t0 = System.nanoTime();
        MvcResult r = mvc.perform(get(uri).header("Authorization", auth)).andReturn();
        r.getResponse().getContentAsString();
        long dt = System.nanoTime() - t0;
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return dt;
    }

    // ------------------------------------------------------------------ DB-07: EXPLAIN

    /**
     * Captura el SQL real que genera Hibernate (logger {@code org.hibernate.SQL} en DEBUG solo durante una peticion por
     * combinacion) y le aplica {@code EXPLAIN} por JDBC con los parametros enlazados. Solo informa.
     */
    private String explicar(List<Combo> combos, String auth) throws Exception {
        StringBuilder sb = new StringBuilder();
        Logger logger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level anterior = logger.getLevel();
        for (Combo combo : combos) {
            ListAppender<ILoggingEvent> captura = new ListAppender<>();
            captura.start();
            logger.addAppender(captura);
            logger.setLevel(Level.DEBUG);
            try {
                mvc.perform(get(URI.create(URL + combo.query() + "&size=50&page=0")).header("Authorization", auth)).andReturn();
            } finally {
                logger.detachAppender(captura);
                logger.setLevel(anterior);
            }
            sb.append("Combinacion: ").append(combo.nombre()).append('\n');
            for (ILoggingEvent ev : captura.list) {
                String sql = ev.getFormattedMessage();
                if (!sql.startsWith("select") || !sql.contains("ubicacion_hallazgo")) {
                    continue;
                }
                boolean esConteo = sql.startsWith("select count(");
                sb.append(esConteo ? "  [conteo]   " : "  [pagina]   ").append(sql.length() > 260 ? sql.substring(0, 260) + "..." : sql)
                        .append('\n');
                sb.append(planExplain(sql, combo, 50));
            }
        }
        return sb.toString();
    }

    private String planExplain(String sql, Combo combo, int size) throws SQLException {
        long marcadores = sql.chars().filter(ch -> ch == '?').count();
        // Hibernate enlaza primero las condiciones en el orden de clausulaWhere (especie, desde, hasta, investigador,
        // departamento) y despues el limite (y el desplazamiento si lo hay)
        List<Object> params = ordenarParametros(combo);
        long extra = marcadores - params.size();
        if (extra == 1) {
            params.add(size);
        } else if (extra == 2) {
            params.add(0);
            params.add(size);
        }
        StringBuilder sb = new StringBuilder();
        try (java.sql.Connection c = jdbc.getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("EXPLAIN " + sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof LocalDate d) {
                    ps.setObject(i + 1, d);
                } else if (p instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else {
                    ps.setString(i + 1, p.toString());
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append(String.format("      tabla=%-4s tipo=%-6s key=%-34s filas_est=%-6s extra=%s%n", rs.getString("table"),
                            rs.getString("type"), rs.getString("key"), rs.getString("rows"), rs.getString("Extra")));
                }
            }
        }
        return sb.toString();
    }

    /** Reordena los parametros de la combinacion al orden en que el repositorio los enlaza. */
    private static List<Object> ordenarParametros(Combo combo) {
        List<Object> texto = new ArrayList<>();
        List<Object> fechas = new ArrayList<>();
        List<Object> resto = new ArrayList<>();
        for (Object p : combo.params()) {
            if (p instanceof LocalDate) {
                fechas.add(p);
            } else if (p.toString().endsWith("%")) {
                texto.add(p); // especie primero
            } else {
                resto.add(p);
            }
        }
        // orden: especie, desde, hasta, y luego investigador o departamento (ninguna combinacion usa ambos)
        List<Object> orden = new ArrayList<>(texto);
        orden.addAll(fechas);
        orden.addAll(resto);
        return orden;
    }

    // ------------------------------------------------------------------ informe

    private void escribirInforme(long msSiembra, long msAnalyze, double usDescifrado, String explain,
                                 List<Resultado> resultados, List<Resultado> peorCaso, List<Combo> combos) throws Exception {
        ZonedDateTime ahora = ZonedDateTime.now(ZoneId.systemDefault());
        informe.append("=== Rendimiento de la busqueda interna con 10.000 hallazgos (HU-13 c.3, CG-08 / HB-23 / DB-07) ===\n");
        informe.append("Fecha:        ").append(ahora.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n');
        informe.append("Commit:       ").append(commit()).append('\n');
        informe.append("CPU:          ").append(cpu()).append(" (").append(Runtime.getRuntime().availableProcessors())
                .append(" hilos logicos)\n");
        informe.append("Memoria JVM:  max ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MiB\n");
        informe.append("JVM / SO:     ").append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.version"))
                .append(" / ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append('\n');
        informe.append("MySQL:        ").append(jdbc.queryForObject("SELECT VERSION()", String.class)).append(" (Testcontainers)\n");
        informe.append("Siembra:      ").append(TOTAL).append(" hallazgos (").append(PORCENTAJE_EN_PELIGRO).append(" % EN_PELIGRO) en ")
                .append(msSiembra).append(" ms (incluye cifrar con AES-256-GCM real); ANALYZE TABLE ").append(msAnalyze).append(" ms\n");
        informe.append("Descifrado aislado (AES-GCM, CifradorCoordenadas): ").append(String.format("%.1f", usDescifrado))
                .append(" us por registro\n");
        informe.append("Combinaciones de filtros (rotan en las peticiones):\n");
        for (Combo c : combos) {
            informe.append("  - ").append(c.nombre()).append('\n');
        }
        informe.append("Por caso: ").append(CALENTAMIENTO).append(" peticiones de calentamiento + ").append(MEDIDAS)
                .append(" medidas. Percentiles por rango mas cercano. Umbral: p95 < ").append(UMBRAL_P95_MS).append(" ms.\n\n");

        tabla("CG-08 (se afirma p95 < 3000 ms)", resultados);
        tabla("Peor caso INFORMATIVO (no se afirma; 10.000 coincidencias y paginacion profunda)", peorCaso);

        informe.append("HB-23 (descifrados por peticion, spy sobre ServicioDescifrado.descifrar):\n");
        for (Resultado r : resultados) {
            informe.append(String.format("  %-48s min=%d max=%d media=%.1f (size=%d)%n", r.caso(), r.descifradosMin(), r.descifradosMax(),
                    r.descifradosMedia(), r.size()));
        }
        informe.append("\nDB-07 (EXPLAIN del SQL real de Hibernate; solo informa):\n").append(explain);
        informe.append("\nLimite: MockMvc mide el servicio en proceso, sin red, sin gateway, sin JWT real y sin concurrencia.\n");

        System.out.println("\n" + informe);
        Path dir = Path.of("target", "rendimiento");
        Files.createDirectories(dir);
        String marca = ahora.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.writeString(dir.resolve("busqueda-10k-" + marca + ".txt"), informe.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("busqueda-10k-ultimo.txt"), informe.toString(), StandardCharsets.UTF_8);
        // CSV para promediar corridas
        StringBuilder csv = new StringBuilder("caso;p50_ms;p95_ms;p99_ms;max_ms;descifrados_max;calentamiento_max_ms\n");
        List<Resultado> todos = new ArrayList<>(resultados);
        todos.addAll(peorCaso);
        for (Resultado r : todos) {
            csv.append(String.format(java.util.Locale.ROOT, "%s;%.1f;%.1f;%.1f;%.1f;%d;%.1f%n", r.caso(), r.p(0.50), r.p(0.95), r.p(0.99),
                    r.max(), r.descifradosMax(), r.maxCalentamientoMs()));
        }
        Files.writeString(dir.resolve("busqueda-10k-" + marca + ".csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    private void tabla(String titulo, List<Resultado> resultados) {
        informe.append(titulo).append('\n');
        informe.append(String.format("  %-62s %9s %9s %9s %9s %12s %s%n", "caso", "p50(ms)", "p95(ms)", "p99(ms)", "max(ms)",
                "calent.max", "total coincidencias"));
        Map<String, Resultado> unicos = new LinkedHashMap<>();
        resultados.forEach(r -> unicos.put(r.caso(), r));
        for (Resultado r : unicos.values()) {
            informe.append(String.format(java.util.Locale.ROOT, "  %-62s %9.1f %9.1f %9.1f %9.1f %12.1f %d..%d%n", r.caso(), r.p(0.50),
                    r.p(0.95), r.p(0.99), r.max(), r.maxCalentamientoMs(), r.totalMin(), r.totalMax()));
        }
        informe.append('\n');
    }

    private static String cpu() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (l.startsWith("model name")) {
                    return l.substring(l.indexOf(':') + 1).trim();
                }
            }
        } catch (Exception e) {
            // se usa el respaldo
        }
        return System.getProperty("os.arch");
    }

    private static String commit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String salida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            Process d = new ProcessBuilder("git", "status", "--porcelain").redirectErrorStream(true).start();
            boolean sucio = !new String(d.getInputStream().readAllBytes(), StandardCharsets.UTF_8).isBlank();
            d.waitFor(5, TimeUnit.SECONDS);
            return salida + (sucio ? " (+cambios sin commit)" : "");
        } catch (Exception e) {
            return "desconocido";
        }
    }
}
