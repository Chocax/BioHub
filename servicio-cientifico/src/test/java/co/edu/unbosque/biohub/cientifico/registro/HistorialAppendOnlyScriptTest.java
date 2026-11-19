package co.edu.unbosque.biohub.cientifico.registro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RNF10 / HU-05 c.2: en Aiven, {@code cientifico_app} solo puede hacer UPDATE y DELETE en las tablas que lista
 * {@code deploy/aiven/03-registro-historial-append-only.sql.example}. El historial queda fuera a propósito.
 *
 * <p>Esta prueba evita que ese script se quede atrás: si alguien crea una tabla nueva con una migración y no decide si
 * va en el script, el build falla. Las únicas tablas que no van son las del historial (append-only), los catálogos
 * que solo toca Flyway y la tabla propia de Flyway.
 */
class HistorialAppendOnlyScriptTest extends BaseRegistroMySqlTest {

    static final Path SCRIPT = Path.of("..", "deploy", "aiven", "03-registro-historial-append-only.sql.example");

    /** Sin UPDATE ni DELETE para la aplicación. */
    static final Set<String> SIN_MODIFICACION = Set.of("hallazgo_version", "hallazgo_auditoria",
            "ubicacion_departamento", "ubicacion_region", "flyway_schema_history");

    @Test
    @DisplayName("toda tabla de cientifico está en el script de permisos o es historial, catálogo o Flyway")
    void elScriptCubreTodasLasTablas() throws IOException {
        Set<String> enScript = new TreeSet<>();
        Matcher m = Pattern.compile("GRANT UPDATE, DELETE ON `cientifico`\\.`([a-z_]+)`")
                .matcher(Files.readString(SCRIPT, StandardCharsets.UTF_8));
        while (m.find()) {
            enScript.add(m.group(1));
        }
        Set<String> tablas = new TreeSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                        + " AND table_type = 'BASE TABLE'", String.class));
        tablas.removeAll(SIN_MODIFICACION);

        assertThat(enScript)
                .as("Agrega la tabla nueva a deploy/aiven/03-registro-historial-append-only.sql.example"
                        + " (o a SIN_MODIFICACION si es historial o catálogo)")
                .isEqualTo(tablas);
        assertThat(enScript).doesNotContainAnyElementsOf(Set.of("hallazgo_version", "hallazgo_auditoria"));
    }
}
