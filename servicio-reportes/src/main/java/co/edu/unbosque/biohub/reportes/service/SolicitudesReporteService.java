package co.edu.unbosque.biohub.reportes.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unbosque.biohub.reportes.domain.ConteoDepartamento;
import co.edu.unbosque.biohub.reportes.domain.EstadoSolicitud;
import co.edu.unbosque.biohub.reportes.domain.HallazgoReporteRepository;
import co.edu.unbosque.biohub.reportes.domain.OrigenSolicitud;
import co.edu.unbosque.biohub.reportes.domain.ReporteArchivo;
import co.edu.unbosque.biohub.reportes.domain.ReporteArchivoRepository;
import co.edu.unbosque.biohub.reportes.domain.SolicitudReporte;
import co.edu.unbosque.biohub.reportes.domain.SolicitudReporteRepository;

@Service
public class SolicitudesReporteService {

    private static final Logger log = LoggerFactory.getLogger(SolicitudesReporteService.class);
    static final ZoneId ZONA = ZoneId.of("America/Bogota");

    private final SolicitudReporteRepository solicitudes;
    private final ReporteArchivoRepository archivos;
    private final HallazgoReporteRepository hallazgos;
    private final ApplicationEventPublisher eventos;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock reloj;

    @SuppressWarnings("java:S107")
    public SolicitudesReporteService(SolicitudReporteRepository solicitudes, ReporteArchivoRepository archivos,
                                     HallazgoReporteRepository hallazgos, ApplicationEventPublisher eventos,
                                     TransactionTemplate tx, ObjectMapper json, Clock reloj) {
        this.solicitudes = solicitudes;
        this.archivos = archivos;
        this.hallazgos = hallazgos;
        this.eventos = eventos;
        this.tx = tx;
        this.json = json;
        this.reloj = reloj;
    }

    /** Registra la solicitud. El periodo no puede ser un mes futuro. */
    public SolicitudReporte solicitar(YearMonth periodo, String usuario, OrigenSolicitud origen) {
        if (periodo == null) {
            throw new IllegalArgumentException("El periodo es obligatorio (AAAA-MM).");
        }
        if (periodo.isAfter(YearMonth.now(reloj.withZone(ZONA))) || periodo.getYear() < 2000) {
            throw new IllegalArgumentException("El periodo debe ser un mes entre 2000 y el mes actual.");
        }
        return tx.execute(estado -> {
            SolicitudReporte nueva = solicitudes.save(
                new SolicitudReporte(periodo.toString(), origen, usuario, Instant.now(reloj)));
            eventos.publishEvent(new SolicitudReporteCreada(nueva.getId()));
            return nueva;
        });
    }

    /** Reporte del mes anterior, una sola vez por mes (tarea programada). Devuelve false si ya existía. */
    public boolean solicitarMesAnteriorSiFalta() {
        YearMonth anterior = YearMonth.now(reloj.withZone(ZONA)).minusMonths(1);
        if (solicitudes.existsByPeriodoAndOrigen(anterior.toString(), OrigenSolicitud.PROGRAMADO)) {
            return false;
        }
        solicitar(anterior, "sistema", OrigenSolicitud.PROGRAMADO);
        return true;
    }

    /**
     * Genera el reporte. Si el mensaje llega dos veces o la solicitud ya no está pendiente, no hace nada.
     * Cualquier fallo (incluyendo errores fatales) deja la solicitud en FALLIDO para evitar bloqueos.
     */
    public void procesar(UUID id) {
        Boolean empezo = tx.execute(estado -> solicitudes.findById(id)
            .map(s -> s.iniciar(Instant.now(reloj)))
            .orElse(false));
        if (!Boolean.TRUE.equals(empezo)) {
            return;
        }
        try {
            SolicitudReporte solicitud = solicitudes.findById(id).orElseThrow(SolicitudNoEncontradaException::new);
            YearMonth periodo = YearMonth.parse(solicitud.getPeriodo());
            List<IndicePresion> indices = CalculadorIndicePresion.calcular(
                consolidarDepartamentos(hallazgos.contarPorDepartamento(periodo.atDay(1), periodo.atEndOfMonth())),
                consolidarDepartamentos(hallazgos.contarPorDepartamento(periodo.minusMonths(3).atDay(1),
                    periodo.minusMonths(1).atEndOfMonth())));
            Map<String, Object> resumen = resumen(periodo, indices);
            String resumenJson = json.writeValueAsString(resumen);
            byte[] csv = csv(indices);
            byte[] pdf = pdf(periodo, indices, resumen);
            tx.executeWithoutResult(estado -> {
                archivos.save(new ReporteArchivo(id, csv, pdf));
                solicitudes.findById(id).ifPresent(s -> s.terminar(resumenJson, Instant.now(reloj)));
            });
            log.info("Reporte {} del periodo {} listo: {} departamentos", id, periodo, indices.size());
        } catch (Throwable t) {
            log.warn("El reporte {} falló: {}", id, t.getClass().getSimpleName());
            marcarFallido(id, "No se pudo generar el reporte (" + t.getClass().getSimpleName() + ").");
            if (t instanceof Error err) {
                throw err;
            }
        }
    }

    public void marcarFallido(UUID id, String motivo) {
        tx.executeWithoutResult(estado -> solicitudes.findById(id)
            .ifPresent(s -> {
                if (s.getEstado() == EstadoSolicitud.PENDIENTE || s.getEstado() == EstadoSolicitud.EN_PROCESO) {
                    s.fallar(motivo, Instant.now(reloj));
                }
            }));
    }

    public int liberarSolicitudesColgadas(Instant limite) {
        Integer cantidad = tx.execute(estado -> {
            List<SolicitudReporte> colgadas = solicitudes.findByEstadoInAndSolicitadoEnBefore(
                List.of(EstadoSolicitud.PENDIENTE, EstadoSolicitud.EN_PROCESO), limite);
            Instant ahora = Instant.now(reloj);
            for (SolicitudReporte s : colgadas) {
                s.fallar("La solicitud excedió el tiempo máximo de procesamiento.", ahora);
            }
            return colgadas.size();
        });
        return cantidad != null ? cantidad : 0;
    }

    public SolicitudReporte obtener(UUID id) {
        return solicitudes.findById(id).orElseThrow(SolicitudNoEncontradaException::new);
    }

    public List<SolicitudReporte> recientes() {
        return solicitudes.findTop20ByOrderBySolicitadoEnDesc();
    }

    /** Archivos de un reporte LISTO; si no, 409 con el estado actual. */
    public ReporteArchivo archivos(UUID id) {
        SolicitudReporte s = obtener(id);
        if (s.getEstado() != EstadoSolicitud.LISTO) {
            throw new ReporteNoListoException(s.getEstado().name());
        }
        return archivos.findById(id).orElseThrow(() -> new ReporteNoListoException(s.getEstado().name()));
    }

    private static List<ConteoDepartamento> consolidarDepartamentos(List<ConteoDepartamento> crudos) {
        Map<String, long[]> acum = new LinkedHashMap<>();
        for (ConteoDepartamento c : crudos) {
            String depto = NormalizadorDepartamento.normalizar(c.departamento());
            long[] v = acum.computeIfAbsent(depto, k -> new long[3]);
            v[0] += c.hallazgos();
            v[1] += c.enPeligro();
            v[2] += c.especies();
        }
        return acum.entrySet().stream()
            .map(e -> new ConteoDepartamento(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
            .toList();
    }

    private static Map<String, Object> resumen(YearMonth periodo, List<IndicePresion> indices) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("periodo", periodo.toString());
        r.put("totalHallazgos", indices.stream().mapToLong(IndicePresion::hallazgos).sum());
        r.put("totalEnPeligro", indices.stream().mapToLong(IndicePresion::enPeligro).sum());
        r.put("departamentos", indices.size());
        r.put("zonasEnAlerta", indices.stream().filter(IndicePresion::alerta).map(IndicePresion::departamento).toList());
        r.put("indices", indices);
        return r;
    }

    static byte[] csv(List<IndicePresion> indices) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Departamento;Hallazgos;En peligro;Especies;Proporción en peligro;Promedio en peligro (3 meses previos);")
            .append("Crecimiento;Índice de presión;Nivel;Zona en alerta\r\n");
        for (IndicePresion i : indices) {
            sb.append(celda(NormalizadorDepartamento.normalizar(i.departamento()))).append(';')
                .append(i.hallazgos()).append(';')
                .append(i.enPeligro()).append(';')
                .append(i.especies()).append(';')
                .append(numero(i.proporcionEnPeligro())).append(';')
                .append(numero(i.promedioEnPeligroPrevio())).append(';')
                .append(numero(i.crecimiento())).append(';')
                .append(numero(i.indice())).append(';')
                .append(i.nivel()).append(';')
                .append(i.alerta() ? "Sí" : "No").append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String celda(String valor) {
        String v = valor == null ? "" : valor;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")
            ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    public byte[] pdfDe(UUID id) {
        ReporteArchivo guardado = archivos(id);
        SolicitudReporte s = obtener(id);
        return indicesDe(s).map(indices -> pdf(YearMonth.parse(s.getPeriodo()), indices, Map.of(),
                s.getTerminadoEn() == null ? LocalDate.now(ZONA) : LocalDate.ofInstant(s.getTerminadoEn(), ZONA)))
            .orElseGet(guardado::getPdf);
    }

    public byte[] csvDe(UUID id) {
        ReporteArchivo guardado = archivos(id);
        return indicesDe(obtener(id)).map(SolicitudesReporteService::csv).orElseGet(guardado::getCsv);
    }

    private java.util.Optional<List<IndicePresion>> indicesDe(SolicitudReporte s) {
        if (s.getResumenJson() == null) {
            return java.util.Optional.empty();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode nodo = json.readTree(s.getResumenJson()).get("indices");
            if (nodo == null || !nodo.isArray()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(java.util.Arrays.asList(json.treeToValue(nodo, IndicePresion[].class)));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("El resumen del reporte {} no se pudo leer: se entrega el archivo guardado", s.getId());
            return java.util.Optional.empty();
        }
    }

    static byte[] pdf(YearMonth periodo, List<IndicePresion> indices, Map<String, Object> resumen) {
        return pdf(periodo, indices, resumen, LocalDate.now(ZONA));
    }

    static byte[] pdf(YearMonth periodo, List<IndicePresion> indices, Map<String, Object> resumen, LocalDate generado) {
        return PdfPresion.generar(periodo, indices, generado);
    }

    private static String numero(double valor) {
        return String.format(Locale.ROOT, "%.2f", valor).replace('.', ',');
    }
}
