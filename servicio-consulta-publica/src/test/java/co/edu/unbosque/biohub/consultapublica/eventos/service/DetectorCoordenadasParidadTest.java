package co.edu.unbosque.biohub.consultapublica.eventos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SEC-I27-1 / D4: el detector existe en dos copias (servicio-cientifico y servicio-consulta-publica) sin módulo común.
 * Esta prueba es IDÉNTICA en las dos y lee el mismo corpus ({@code detector-corpus.txt}, también idéntico):
 * <ol>
 *   <li>cada texto del corpus debe dar el resultado esperado (comportamiento);</li>
 *   <li>el bloque {@code <reglas-compartidas>} del código fuente debe tener el resumen SHA-256 que figura en el corpus
 *       (estructura). Si alguien cambia una copia y no la otra, esta prueba falla en la que se modificó, o en la otra
 *       al actualizar el resumen.</li>
 * </ol>
 * Para cambiar las reglas: editar el bloque en las dos copias, actualizar el corpus y el resumen en las dos.
 */
@DisplayName("SEC-I27-1: paridad del detector de coordenadas entre servicio-cientifico y servicio-consulta-publica")
class DetectorCoordenadasParidadTest {

    private static final String CORPUS = "/detector-corpus.txt";

    @Test
    @DisplayName("el corpus compartido da el resultado esperado")
    void corpusCompartido() throws IOException {
        List<String> fallos = new ArrayList<>();
        int casos = 0;
        for (String linea : leerCorpus()) {
            if (linea.isBlank() || linea.startsWith("#") || linea.startsWith("@")) {
                continue;
            }
            char marca = linea.charAt(0);
            String texto = interpretar(linea.substring(2));
            boolean detectado = DetectorCoordenadas.pareceCoordenada(texto);
            boolean esperado = marca != '-';
            casos++;
            if (detectado != esperado) {
                fallos.add(marca + "|" + linea.substring(2) + " -> " + detectado);
            }
        }
        assertThat(casos).as("el corpus no puede estar vacío").isGreaterThan(80);
        assertThat(fallos).as("textos con resultado distinto del esperado").isEmpty();
    }

    @Test
    @DisplayName("el bloque de reglas del código fuente coincide con el resumen acordado")
    void bloqueDeReglasIdentico() throws IOException {
        Path fuente = Path.of("src/main/java", DetectorCoordenadas.class.getName().replace('.', '/') + ".java");
        String codigo = Files.readString(fuente, StandardCharsets.UTF_8);
        int ini = codigo.indexOf("// <reglas-compartidas>");
        int fin = codigo.indexOf("// </reglas-compartidas>");
        assertThat(ini).as("marca de inicio").isNotNegative();
        assertThat(fin).as("marca de fin").isGreaterThan(ini);
        String bloque = codigo.substring(ini, fin).replaceAll("\\s+", "");
        String resumen;
        try {
            resumen = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bloque.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String esperado = leerCorpus().stream().filter(l -> l.startsWith("@reglas-sha256="))
                .map(l -> l.substring("@reglas-sha256=".length()).strip()).findFirst()
                .orElseGet(() -> fail("falta la línea @reglas-sha256= en el corpus"));
        assertThat(resumen).as("las reglas cambiaron: actualice el corpus y el resumen en las DOS copias").isEqualTo(esperado);
    }

    private static List<String> leerCorpus() throws IOException {
        try (InputStream in = DetectorCoordenadasParidadTest.class.getResourceAsStream(CORPUS)) {
            assertThat(in).as("recurso " + CORPUS).isNotNull();
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        }
    }

    /** Interpreta {@code \}{@code uXXXX} y {@code \}{@code t} del corpus. */
    private static String interpretar(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == 'u' && i + 6 <= s.length()) {
                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 5;
            } else if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == 't') {
                sb.append('\t');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
