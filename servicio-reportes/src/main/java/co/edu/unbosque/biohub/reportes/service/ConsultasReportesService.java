package co.edu.unbosque.biohub.reportes.service;

import co.edu.unbosque.biohub.reportes.domain.HallazgoReporte;
import co.edu.unbosque.biohub.reportes.domain.HallazgoReporteRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsultasReportesService {

    private final HallazgoReporteRepository repository;

    public ConsultasReportesService(HallazgoReporteRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ResumenAnalitico generarResumen(String departamento, String familia, String estado,
                                           LocalDate desde, LocalDate hasta) {
        validarRangoFechas(desde, hasta);
        List<HallazgoReporte> filas = listarDetalle(departamento, familia, estado, desde, hasta);

        long totalHallazgos = filas.size();
        long especiesDistintas = filas.stream()
            .map(h -> h.getNombreCientifico().toLowerCase())
            .distinct()
            .count();
        long enPeligro = filas.stream()
            .filter(h -> "EN_PELIGRO".equalsIgnoreCase(h.getEstadoConservacion()))
            .count();

        List<ConteoCategoria> porDepartamento = agruparYOrdenar(
            filas, h -> NormalizadorDepartamento.normalizar(h.getDepartamento()));
        List<ConteoCategoria> porFamilia = agruparYOrdenar(filas, HallazgoReporte::getFamilia);
        List<ConteoCategoria> porEstado = agruparYOrdenar(filas, HallazgoReporte::getEstadoConservacion);

        return new ResumenAnalitico(
            Instant.now(),
            totalHallazgos,
            especiesDistintas,
            enPeligro,
            porDepartamento,
            porFamilia,
            porEstado
        );
    }

    @Transactional(readOnly = true)
    public List<HallazgoReporte> listarDetalle(String departamento, String familia, String estado,
                                               LocalDate desde, LocalDate hasta) {
        validarRangoFechas(desde, hasta);
        String deptoCanonico = normalizarDepartamento(departamento);
        List<HallazgoReporte> filas = repository.buscarFiltrado(
            null, normalizar(familia), normalizar(estado), desde, hasta);
        if (deptoCanonico == null) {
            return filas;
        }
        return filas.stream()
            .filter(h -> deptoCanonico.equals(NormalizadorDepartamento.normalizar(h.getDepartamento())))
            .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportarCsv(String departamento, String familia, String estado,
                              LocalDate desde, LocalDate hasta) {
        List<HallazgoReporte> filas = listarDetalle(departamento, familia, estado, desde, hasta);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF'); // BOM UTF-8 para compatibilidad directa con Excel
        sb.append("hallazgo_id,version,nombre_cientifico,nombre_comun,familia,tipo_especimen,fecha_recoleccion,estado_conservacion,departamento,region\n");
        for (HallazgoReporte h : filas) {
            sb.append(escaparCsv(h.getHallazgoId().toString())).append(',')
                .append(h.getVersion()).append(',')
                .append(escaparCsv(h.getNombreCientifico())).append(',')
                .append(escaparCsv(h.getNombreComun())).append(',')
                .append(escaparCsv(h.getFamilia())).append(',')
                .append(escaparCsv(h.getTipoEspecimen())).append(',')
                .append(h.getFechaRecoleccion()).append(',')
                .append(escaparCsv(h.getEstadoConservacion())).append(',')
                .append(escaparCsv(NormalizadorDepartamento.normalizar(h.getDepartamento()))).append(',')
                .append(escaparCsv(h.getRegion())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] exportarPdf(String departamento, String familia, String estado,
                              LocalDate desde, LocalDate hasta) {
        ResumenAnalitico resumen = generarResumen(departamento, familia, estado, desde, hasta);
        List<HallazgoReporte> filas = listarDetalle(departamento, familia, estado, desde, hasta);

        List<String> lineas = new ArrayList<>();
        lineas.add("BIOHUB - REPORTE DE BIODIVERSIDAD Y CONSERVACION");
        lineas.add("Generado UTC: " + resumen.generadoEn());
        lineas.add("Total hallazgos: " + resumen.totalHallazgos()
            + " | Especies distintas: " + resumen.especiesDistintas()
            + " | En peligro: " + resumen.hallazgosEnPeligro());
        lineas.add("--------------------------------------------------------------------------------");
        lineas.add("DISTRIBUCION POR DEPARTAMENTO:");
        for (ConteoCategoria c : resumen.porDepartamento()) {
            lineas.add(" - " + sanitizarAscii(c.etiqueta()) + ": " + c.cantidad());
        }
        lineas.add("--------------------------------------------------------------------------------");
        lineas.add("DETALLE DE HALLAZGOS (SIN COORDENADAS SENSIBLES):");
        int limite = Math.min(filas.size(), 40);
        for (int i = 0; i < limite; i++) {
            HallazgoReporte h = filas.get(i);
            lineas.add(String.format("%s | %s | %s | %s | %s",
                h.getFechaRecoleccion(),
                sanitizarAscii(h.getNombreCientifico()),
                sanitizarAscii(h.getFamilia()),
                sanitizarAscii(h.getEstadoConservacion()),
                sanitizarAscii(NormalizadorDepartamento.normalizar(h.getDepartamento()))));
        }
        if (filas.size() > limite) {
            lineas.add("... (" + (filas.size() - limite) + " registros adicionales disponibles en CSV)");
        }
        return construirPdfSimple(lineas);
    }

    private static List<ConteoCategoria> agruparYOrdenar(
        List<HallazgoReporte> filas,
        java.util.function.Function<HallazgoReporte, String> extractor) {
        return filas.stream()
            .collect(Collectors.groupingBy(
                h -> {
                    String v = extractor.apply(h);
                    return (v == null || v.isBlank()) ? "SIN_DATO" : v;
                },
                LinkedHashMap::new,
                Collectors.counting()))
            .entrySet().stream()
            .map(e -> new ConteoCategoria(e.getKey(), e.getValue()))
            .sorted(Comparator.comparingLong(ConteoCategoria::cantidad).reversed()
                .thenComparing(ConteoCategoria::etiqueta))
            .toList();
    }

    private static void validarRangoFechas(LocalDate desde, LocalDate hasta) {
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'.");
        }
    }

    private static String normalizar(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String normalizarDepartamento(String s) {
        String limpio = normalizar(s);
        return limpio == null ? null : NormalizadorDepartamento.normalizar(limpio);
    }

    static String escaparCsv(String valor) {
        if (valor == null) {
            return "";
        }
        String seguro = valor;
        if (!seguro.isEmpty()) {
            char primero = seguro.charAt(0);
            if (primero == '=' || primero == '+' || primero == '-' || primero == '@'
                || primero == '\t' || primero == '\r') {
                seguro = "'" + seguro;
            }
        }
        boolean requiereComillas = seguro.contains(",") || seguro.contains("\"")
            || seguro.contains("\n") || seguro.contains("\r");
        String limpio = seguro.replace("\"", "\"\"");
        return requiereComillas ? "\"" + limpio + "\"" : limpio;
    }

    static String sanitizarAscii(String texto) {
        if (texto == null) {
            return "";
        }
        return texto
            .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
            .replace('Á', 'A').replace('É', 'E').replace('Í', 'I').replace('Ó', 'O').replace('Ú', 'U')
            .replace('ñ', 'n').replace('Ñ', 'N')
            .replaceAll("[^\\x20-\\x7E]", "?");
    }

    static byte[] construirPdfSimple(List<String> lineas) {
        StringBuilder flujo = new StringBuilder();
        flujo.append("BT\n/F1 10 Tf\n40 790 Td\n14 TL\n");
        for (int i = 0; i < lineas.size(); i++) {
            String escaped = lineas.get(i)
                .replace("\\", "\\\\")
                .replace("(", "\\(") .replace(")", "\\)");
            if (i == 0) {
                flujo.append("(").append(escaped).append(") Tj\n");
            } else {
                flujo.append("T* (").append(escaped).append(") Tj\n");
            }
        }
        flujo.append("ET\n");
        byte[] streamBytes = flujo.toString().getBytes(StandardCharsets.US_ASCII);

        List<String> objetos = List.of(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n" + flujo + "endstream\nendobj\n",
            "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(header);

        List<Integer> offsets = new ArrayList<>();
        for (String obj : objetos) {
            offsets.add(out.size());
            out.writeBytes(obj.getBytes(StandardCharsets.US_ASCII));
        }

        int xrefPos = out.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objetos.size() + 1).append("\n");
        xref.append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            xref.append(String.format("%010d 00000 n \n", offset));
        }
        xref.append("trailer\n<< /Size ").append(objetos.size() + 1).append(" /Root 1 0 R >>\n");
        xref.append("startxref\n").append(xrefPos).append("\n%%EOF\n");
        out.writeBytes(xref.toString().getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    public record ConteoCategoria(String etiqueta, long cantidad) {}

    public record ResumenAnalitico(
        Instant generadoEn,
        long totalHallazgos,
        long especiesDistintas,
        long hallazgosEnPeligro,
        List<ConteoCategoria> porDepartamento,
        List<ConteoCategoria> porFamilia,
        List<ConteoCategoria> porEstado) {}
}
