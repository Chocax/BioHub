package co.edu.unbosque.biohub.reportes.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF del reporte mensual de presión (HU-09, RF15, RF16) con la línea gráfica de BioHub (D64): encabezado oscuro,
 * cifras, aviso de zonas en alerta, tabla con barra de índice y nivel en color, explicación de cómo leerlo y pie
 * con número de página. Se escribe a mano (PDF 1.4, fuentes base con WinAnsiEncoding): sin librerías externas y con
 * tildes y eñes. Tamaño A4.
 */
final class PdfPresion {

    // Colores de la web interna (galeria.css), en RGB de 0 a 1.
    private static final double[] TINTA = rgb(0x0d0d0c);
    private static final double[] GRAFITO = rgb(0x595855);
    private static final double[] MASILLA = rgb(0xc4c3b6);
    private static final double[] HUESO = rgb(0xe7e5e4);
    private static final double[] TIZA = rgb(0xf1f0ec);
    private static final double[] PERGAMINO = rgb(0xd6d3cb);
    private static final double[] MUSGO = rgb(0x4c6a35);
    private static final double[] MUSGO_CLARO = rgb(0xdfe6cf);
    private static final double[] TERRACOTA = rgb(0x9a3b2c);
    private static final double[] TERRACOTA_CLARO = rgb(0xf2dcd5);
    private static final double[] OCRE = rgb(0x94691d);
    private static final double[] OCRE_CLARO = rgb(0xf1e4c4);
    private static final double[] BLANCO = {1, 1, 1};

    private static final double ANCHO = 595;
    private static final double ALTO = 842;
    private static final double MARGEN = 40;
    private static final double FILA = 20;
    private static final double PIE = 60;

    // Fuentes base del PDF (no se incrustan): /F1 Helvetica, /F2 Helvetica-Bold, /F3 Times-Roman, /F4 Times-Italic.
    private static final String SANS = "F1";
    private static final String SANS_NEGRITA = "F2";
    private static final String SERIF = "F3";
    private static final String SERIF_CURSIVA = "F4";

    private final List<StringBuilder> paginas = new ArrayList<>();
    private StringBuilder pagina;
    private final String periodoTexto;

    private PdfPresion(String periodoTexto) {
        this.periodoTexto = periodoTexto;
    }

    static byte[] generar(YearMonth periodo, List<IndicePresion> indices, LocalDate generado) {
        String mes = periodo.atDay(1).format(DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("es-CO")));
        PdfPresion pdf = new PdfPresion(Character.toUpperCase(mes.charAt(0)) + mes.substring(1));
        pdf.dibujar(indices, generado);
        return pdf.bytes();
    }

    // ------------------------------------------------------------------------------------------------ contenido
    private void dibujar(List<IndicePresion> indices, LocalDate generado) {
        nuevaPagina();
        long hallazgos = indices.stream().mapToLong(IndicePresion::hallazgos).sum();
        long enPeligro = indices.stream().mapToLong(IndicePresion::enPeligro).sum();
        List<String> alertas = indices.stream().filter(IndicePresion::alerta).map(IndicePresion::departamento).toList();

        // Encabezado oscuro, como la tarjeta negra de la galería
        rect(0, ALTO - 112, ANCHO, 112, TINTA);
        texto(MARGEN, ALTO - 50, SERIF, 24, TIZA, "BioHub");
        texto(MARGEN, ALTO - 70, SANS, 7.5, MASILLA, "SISTEMA NACIONAL DE REGISTRO Y VIGILANCIA DE BIODIVERSIDAD");
        textoDerecha(ANCHO - MARGEN, ALTO - 50, SERIF_CURSIVA, 20, TIZA, periodoTexto, 0.42);
        textoDerecha(ANCHO - MARGEN, ALTO - 70, SANS, 7.5, MASILLA,
                "Generado el " + generado.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), 0.5);
        rect(MARGEN, ALTO - 92, 26, 2, MASILLA);

        double y = ALTO - 150;
        texto(MARGEN, y, SANS_NEGRITA, 7.5, GRAFITO, "ANALÍTICA  ·  HU-09");
        y -= 28;
        texto(MARGEN, y, SERIF, 26, TINTA, "Presión sobre los ecosistemas");
        y -= 22;
        for (String linea : partir("Resumen mensual de los hallazgos registrados en BioHub por departamento. El índice combina "
                + "qué parte de lo registrado está en peligro y si eso viene creciendo frente a los tres meses anteriores.", 105)) {
            texto(MARGEN, y, SANS, 9.5, GRAFITO, linea);
            y -= 13;
        }

        // Cifras
        y -= 18;
        String[][] cifras = {
                {"HALLAZGOS", String.valueOf(hallazgos)},
                {"EN PELIGRO", String.valueOf(enPeligro)},
                {"DEPARTAMENTOS", String.valueOf(indices.size())},
                {"ZONAS EN ALERTA", String.valueOf(alertas.size())}};
        double anchoCifra = (ANCHO - 2 * MARGEN - 3 * 10) / 4;
        for (int i = 0; i < cifras.length; i++) {
            double x = MARGEN + i * (anchoCifra + 10);
            rect(x, y - 58, anchoCifra, 64, TIZA);
            texto(x + 12, y - 12, SANS_NEGRITA, 7, GRAFITO, cifras[i][0]);
            texto(x + 12, y - 44, SERIF, 28, i == 3 && !alertas.isEmpty() ? TERRACOTA : TINTA, cifras[i][1]);
        }
        y -= 84;

        // Aviso de zonas en alerta
        List<String> lineasAlerta = alertas.isEmpty()
                ? List.of("Ningún departamento en alerta este mes.")
                : partir(String.join(", ", alertas) + ". Presión alta y más hallazgos en peligro que el promedio "
                        + "de los tres meses anteriores.", 95);
        double altoAviso = 26 + lineasAlerta.size() * 12;
        rect(MARGEN, y - altoAviso, ANCHO - 2 * MARGEN, altoAviso, alertas.isEmpty() ? MUSGO_CLARO : TERRACOTA_CLARO);
        rect(MARGEN, y - altoAviso, 4, altoAviso, alertas.isEmpty() ? MUSGO : TERRACOTA);
        texto(MARGEN + 16, y - 16, SANS_NEGRITA, 9.5, alertas.isEmpty() ? MUSGO : TERRACOTA,
                alertas.isEmpty() ? "Sin zonas en alerta" : "Zonas en alerta");
        double ya = y - 30;
        for (String linea : lineasAlerta) {
            texto(MARGEN + 16, ya, SANS, 9, TINTA, linea);
            ya -= 12;
        }
        y -= altoAviso + 28;

        // Tabla
        texto(MARGEN, y, SERIF, 15, TINTA, "Índice por departamento");
        y -= 16;
        if (indices.isEmpty()) {
            texto(MARGEN, y - 10, SANS, 9.5, GRAFITO, "Sin hallazgos en el periodo.");
            y -= 30;
        } else {
            y = encabezadoTabla(y);
            for (int k = 0; k < indices.size(); k++) {
                if (y - FILA < PIE + 10) {
                    nuevaPagina();
                    y = encabezadoContinuacion();
                    y = encabezadoTabla(y);
                }
                fila(y, indices.get(k), k % 2 == 1);
                y -= FILA;
            }
            y -= 22;
        }

        // Cómo leerlo
        List<String[]> guia = List.of(
                new String[] {"Índice de presión (0 a 100)",
                        "100 × (0,7 × proporción de hallazgos en peligro del mes + 0,3 × crecimiento de los hallazgos en "
                                + "peligro frente al promedio de los tres meses anteriores)."},
                new String[] {"Nivel", "Alto desde 50, medio desde 25 y bajo por debajo de 25."},
                new String[] {"Zona en alerta", "Nivel alto y más hallazgos en peligro que el promedio de los tres meses anteriores."},
                new String[] {"En peligro", "Hallazgos de especies en categoría UICN CR (en peligro crítico) o EN (en peligro)."},
                new String[] {"Tendencia", "Compara los hallazgos en peligro del mes con el promedio de los tres anteriores."},
                new String[] {"Detalle", "El CSV del mismo reporte trae todas las cifras, también las proporciones y el crecimiento."});
        double altoGuia = 40;
        for (String[] g : guia) {
            altoGuia += 13 * partir(g[1], 78).size() + 4;
        }
        if (y - altoGuia < PIE + 10) {
            nuevaPagina();
            y = encabezadoContinuacion();
        }
        texto(MARGEN, y, SERIF, 15, TINTA, "Cómo leer este reporte");
        y -= 22;
        for (String[] g : guia) {
            texto(MARGEN, y, SANS_NEGRITA, 8.5, TINTA, g[0]);
            for (String linea : partir(g[1], 78)) {
                texto(MARGEN + 150, y, SANS, 8.5, GRAFITO, linea);
                y -= 13;
            }
            y -= 4;
        }
    }

    private double encabezadoContinuacion() {
        texto(MARGEN, ALTO - 48, SERIF, 14, TINTA, "BioHub");
        textoDerecha(ANCHO - MARGEN, ALTO - 48, SANS, 8, GRAFITO, "Presión sobre los ecosistemas  ·  " + periodoTexto, 0.5);
        rect(MARGEN, ALTO - 60, ANCHO - 2 * MARGEN, 0.7, PERGAMINO);
        return ALTO - 90;
    }

    // Columnas de la tabla (x de inicio o borde derecho para las cifras)
    private static final double C_DEPTO = MARGEN + 10;
    private static final double C_HALLAZGOS = MARGEN + 210;
    private static final double C_PELIGRO = MARGEN + 268;
    private static final double C_ESPECIES = MARGEN + 318;
    private static final double C_INDICE = MARGEN + 334;
    private static final double C_NIVEL = MARGEN + 425;
    private static final double C_TENDENCIA = MARGEN + 470;

    private double encabezadoTabla(double y) {
        rect(MARGEN, y - FILA, ANCHO - 2 * MARGEN, FILA, HUESO);
        double base = y - 13;
        texto(C_DEPTO, base, SANS_NEGRITA, 7, GRAFITO, "DEPARTAMENTO");
        textoDerecha(C_HALLAZGOS, base, SANS_NEGRITA, 7, GRAFITO, "HALLAZGOS", 0.62);
        textoDerecha(C_PELIGRO, base, SANS_NEGRITA, 7, GRAFITO, "EN PELIGRO", 0.62);
        textoDerecha(C_ESPECIES, base, SANS_NEGRITA, 7, GRAFITO, "ESPECIES", 0.62);
        texto(C_INDICE, base, SANS_NEGRITA, 7, GRAFITO, "ÍNDICE");
        texto(C_NIVEL, base, SANS_NEGRITA, 7, GRAFITO, "NIVEL");
        texto(C_TENDENCIA, base, SANS_NEGRITA, 7, GRAFITO, "TENDENCIA");
        return y - FILA;
    }

    private void fila(double y, IndicePresion i, boolean alterna) {
        if (alterna) {
            rect(MARGEN, y - FILA, ANCHO - 2 * MARGEN, FILA, TIZA);
        }
        double base = y - 13.5;
        double xNombre = C_DEPTO;
        if (i.alerta()) {
            rect(C_DEPTO, base + 1, 5, 5, TERRACOTA);
            xNombre += 10;
        }
        texto(xNombre, base, i.alerta() ? SANS_NEGRITA : SANS, 9, TINTA, recortar(i.departamento(), 28));
        textoDerecha(C_HALLAZGOS, base, SANS, 9, TINTA, String.valueOf(i.hallazgos()), 0.556);
        textoDerecha(C_PELIGRO, base, SANS, 9, TINTA, String.valueOf(i.enPeligro()), 0.556);
        textoDerecha(C_ESPECIES, base, SANS, 9, TINTA, String.valueOf(i.especies()), 0.556);

        double[][] colores = colores(i.nivel());
        double ancho = 54;
        rect(C_INDICE, base + 1, ancho, 5, HUESO);
        rect(C_INDICE, base + 1, Math.max(0.8, ancho * Math.min(100, i.indice()) / 100), 5, colores[0]);
        texto(C_INDICE + ancho + 6, base, SANS, 8, TINTA, String.format(Locale.ROOT, "%.1f", i.indice()));

        rect(C_NIVEL - 3, base - 4, 38, 13, colores[1]);
        texto(C_NIVEL + 2, base, SANS_NEGRITA, 7, colores[0], nivel(i.nivel()));

        String tendencia = i.enPeligro() > i.promedioEnPeligroPrevio() ? "Sube"
                : i.enPeligro() < i.promedioEnPeligroPrevio() ? "Baja" : "Igual";
        texto(C_TENDENCIA, base, SANS, 8.5, TINTA, tendencia + String.format(Locale.ROOT, " (prom. %.1f)",
                i.promedioEnPeligroPrevio()));
    }

    private static double[][] colores(String nivel) {
        return switch (nivel == null ? "" : nivel) {
            case "ALTO" -> new double[][] {TERRACOTA, TERRACOTA_CLARO};
            case "MEDIO" -> new double[][] {OCRE, OCRE_CLARO};
            default -> new double[][] {MUSGO, MUSGO_CLARO};
        };
    }

    private static String nivel(String nivel) {
        return switch (nivel == null ? "" : nivel) {
            case "ALTO" -> "ALTO";
            case "MEDIO" -> "MEDIO";
            default -> "BAJO";
        };
    }

    // --------------------------------------------------------------------------------------------- primitivas
    private void nuevaPagina() {
        pagina = new StringBuilder();
        paginas.add(pagina);
    }

    private void rect(double x, double y, double w, double h, double[] color) {
        pagina.append(String.format(Locale.ROOT, "%.3f %.3f %.3f rg %.2f %.2f %.2f %.2f re f%n",
                color[0], color[1], color[2], x, y, w, h));
    }

    private void texto(double x, double y, String fuente, double tamano, double[] color, String texto) {
        pagina.append(String.format(Locale.ROOT, "BT /%s %.1f Tf %.3f %.3f %.3f rg %.2f %.2f Td (%s) Tj ET%n",
                fuente, tamano, color[0], color[1], color[2], x, y, escapar(texto)));
    }

    /** Alinea a la derecha con un ancho medio por carácter (las cifras de Helvetica miden 0,556 em exactos). */
    private void textoDerecha(double derecha, double y, String fuente, double tamano, double[] color, String texto,
                              double emPorCaracter) {
        texto(derecha - texto.length() * tamano * emPorCaracter, y, fuente, tamano, color, texto);
    }

    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null) {
            return "";
        }
        return texto.length() <= maximo ? texto : texto.substring(0, maximo - 1) + "…";
    }

    /** Parte un texto en líneas de a lo sumo {@code columnas} caracteres, sin cortar palabras. */
    static List<String> partir(String texto, int columnas) {
        List<String> lineas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split(" ")) {
            if (actual.length() > 0 && actual.length() + 1 + palabra.length() > columnas) {
                lineas.add(actual.toString());
                actual.setLength(0);
            }
            if (actual.length() > 0) {
                actual.append(' ');
            }
            actual.append(palabra);
        }
        if (actual.length() > 0) {
            lineas.add(actual.toString());
        }
        return lineas;
    }

    private static double[] rgb(int color) {
        return new double[] {((color >> 16) & 0xff) / 255.0, ((color >> 8) & 0xff) / 255.0, (color & 0xff) / 255.0};
    }

    // ------------------------------------------------------------------------------------------- archivo PDF
    private byte[] bytes() {
        int total = paginas.size();
        for (int n = 0; n < total; n++) {
            pagina = paginas.get(n);
            rect(MARGEN, PIE - 18, ANCHO - 2 * MARGEN, 0.7, PERGAMINO);
            texto(MARGEN, PIE - 32, SANS, 7.5, GRAFITO, "BioHub  ·  Sistema Nacional de Registro y Vigilancia de Biodiversidad");
            textoDerecha(ANCHO - MARGEN, PIE - 32, SANS, 7.5, GRAFITO, "Página " + (n + 1) + " de " + total, 0.5);
        }

        List<byte[]> objetos = new ArrayList<>();
        int primeraPagina = 7; // 1 catálogo, 2 árbol de páginas, 3 a 6 fuentes
        StringBuilder hijos = new StringBuilder();
        for (int n = 0; n < total; n++) {
            hijos.append(primeraPagina + 2 * n).append(" 0 R ");
        }
        objetos.add(latin("<< /Type /Catalog /Pages 2 0 R >>"));
        objetos.add(latin("<< /Type /Pages /Kids [" + hijos.toString().trim() + "] /Count " + total + " >>"));
        for (String base : new String[] {"Helvetica", "Helvetica-Bold", "Times-Roman", "Times-Italic"}) {
            objetos.add(latin("<< /Type /Font /Subtype /Type1 /BaseFont /" + base + " /Encoding /WinAnsiEncoding >>"));
        }
        String recursos = "<< /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R >> >>";
        for (int n = 0; n < total; n++) {
            int contenido = primeraPagina + 2 * n + 1;
            objetos.add(latin("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources " + recursos
                    + " /Contents " + contenido + " 0 R >>"));
            byte[] flujo = latin(paginas.get(n).toString());
            ByteArrayOutputStream contenidoObj = new ByteArrayOutputStream();
            contenidoObj.writeBytes(latin("<< /Length " + flujo.length + " >>\nstream\n"));
            contenidoObj.writeBytes(flujo);
            contenidoObj.writeBytes(latin("\nendstream"));
            objetos.add(contenidoObj.toByteArray());
        }

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        salida.writeBytes(latin("%PDF-1.4\n%âãÏÓ\n"));
        List<Integer> posiciones = new ArrayList<>();
        for (int i = 0; i < objetos.size(); i++) {
            posiciones.add(salida.size());
            salida.writeBytes(latin((i + 1) + " 0 obj\n"));
            salida.writeBytes(objetos.get(i));
            salida.writeBytes(latin("\nendobj\n"));
        }
        int xref = salida.size();
        StringBuilder tabla = new StringBuilder("xref\n0 " + (objetos.size() + 1) + "\n0000000000 65535 f \n");
        for (int p : posiciones) {
            tabla.append(String.format(Locale.ROOT, "%010d 00000 n \n", p));
        }
        tabla.append("trailer\n<< /Size ").append(objetos.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        salida.writeBytes(latin(tabla.toString()));
        return salida.toByteArray();
    }

    /** WinAnsiEncoding coincide con ISO-8859-1 en las letras del español; lo que no cabe sale como "?". */
    private static byte[] latin(String texto) {
        return texto.replace("…", "...").getBytes(StandardCharsets.ISO_8859_1);
    }
}
