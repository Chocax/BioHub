package co.edu.unbosque.biohub.reportes.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdfPresionTest {

    private static IndicePresion indice(String depto, long hallazgos, long enPeligro, double indice, String nivel, boolean alerta) {
        return new IndicePresion(depto, hallazgos, enPeligro, 2, hallazgos == 0 ? 0 : (double) enPeligro / hallazgos,
                0.33, 0.5, indice, nivel, alerta);
    }

    private static String texto(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    @Test
    void generaUnPdfValidoConTildesTituloYZonasEnAlerta() throws IOException {
        byte[] pdf = PdfPresion.generar(YearMonth.of(2026, 10), List.of(
                indice("Chocó", 3, 3, 100, "ALTO", true),
                indice("Bolívar", 4, 1, 32.5, "MEDIO", false),
                indice("Boyacá", 2, 0, 0, "BAJO", false)), LocalDate.of(2026, 11, 19));

        String contenido = texto(pdf);
        assertThat(contenido).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        assertThat(contenido).contains("Presión sobre los ecosistemas", "Octubre de 2026", "Zonas en alerta",
                "Chocó", "Cómo leer este reporte", "Página 1 de 1", "/WinAnsiEncoding");
        Files.write(Path.of("target", "reporte-presion-muestra.pdf"), pdf);
    }

    @Test
    void muchosDepartamentosPasanAOtraPaginaConEncabezado() {
        List<IndicePresion> indices = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            indices.add(indice("Departamento " + i, 5, i % 3, i * 2.5, i % 3 == 0 ? "ALTO" : "BAJO", false));
        }
        String contenido = texto(PdfPresion.generar(YearMonth.of(2026, 9), indices, LocalDate.of(2026, 10, 1)));

        assertThat(contenido).contains("Página 2 de", "Ningún departamento en alerta este mes.", "Departamento 39")
                .doesNotContain("/Count 1 ");
        // Las páginas siguientes repiten el encabezado de la tabla
        assertThat(contenido.split("\\(DEPARTAMENTO\\)", -1).length - 1).isGreaterThan(1);
    }

    @Test
    void sinHallazgosLoDice() {
        String contenido = texto(PdfPresion.generar(YearMonth.of(2026, 8), List.of(), LocalDate.of(2026, 9, 1)));

        assertThat(contenido).contains("Sin hallazgos en el periodo.", "/Count 1");
    }

    @Test
    void partirNoCortaPalabras() {
        assertThat(PdfPresion.partir("uno dos tres cuatro", 8)).containsExactly("uno dos", "tres", "cuatro");
    }
}
