package co.edu.unbosque.biohub.reportes.service;

import co.edu.unbosque.biohub.reportes.domain.EventoProcesadoReporte;
import co.edu.unbosque.biohub.reportes.domain.EventoProcesadoReporteRepository;
import co.edu.unbosque.biohub.reportes.domain.HallazgoReporte;
import co.edu.unbosque.biohub.reportes.domain.HallazgoReporteRepository;
import co.edu.unbosque.biohub.reportes.eventos.domain.EventoSobre;
import co.edu.unbosque.biohub.reportes.eventos.service.EventoInvalidoException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProyectorHallazgosService {

    private final HallazgoReporteRepository hallazgoRepository;
    private final EventoProcesadoReporteRepository eventoRepository;

    public ProyectorHallazgosService(HallazgoReporteRepository hallazgoRepository,
                                     EventoProcesadoReporteRepository eventoRepository) {
        this.hallazgoRepository = hallazgoRepository;
        this.eventoRepository = eventoRepository;
    }

    @Transactional
    public void proyectar(EventoSobre evento) {
        if (eventoRepository.existsById(evento.id())) {
            return;
        }

        Map<String, Object> datos = evento.datos();
        if (datos == null || datos.isEmpty()) {
            throw new EventoInvalidoException("Evento de hallazgo sin datos");
        }

        UUID hallazgoId = parseUuidObligatorio(datos.get("hallazgoId"), "hallazgoId");
        int version = parseVersion(datos.get("version"));
        String nombreCientifico = textoObligatorio(datos.get("nombreCientifico"), "nombreCientifico", 150);
        String nombreComun = textoOpcional(datos.get("nombreComun"), 150);
        String familia = textoObligatorio(datos.get("familia"), "familia", 100);
        String tipoEspecimen = textoOpcional(datos.get("tipoEspecimen"), 60);
        LocalDate fechaRecoleccion = parseFechaObligatoria(datos.get("fechaRecoleccion"), "fechaRecoleccion");
        String estadoConservacion = textoObligatorio(datos.get("estadoConservacion"), "estadoConservacion", 30).toUpperCase();
        String departamento = textoObligatorio(datos.get("departamento"), "departamento", 80);
        String region = textoOpcional(datos.get("region"), 80);

        // Defensa en profundidad (D14/D24): jamás deben viajar coordenadas de especies en peligro
        if ("EN_PELIGRO".equals(estadoConservacion) && contieneCoordenadas(datos)) {
            throw new EventoInvalidoException("Evento de especie en peligro contiene coordenadas prohibidas");
        }

        hallazgoRepository.findById(hallazgoId).ifPresentOrElse(
            existente -> existente.actualizarDesdeSnapshot(
                version, nombreCientifico, nombreComun, familia,
                tipoEspecimen, fechaRecoleccion, estadoConservacion, departamento, region),
            () -> hallazgoRepository.save(new HallazgoReporte(
                hallazgoId, version, nombreCientifico, nombreComun, familia,
                tipoEspecimen, fechaRecoleccion, estadoConservacion, departamento, region))
        );

        eventoRepository.save(new EventoProcesadoReporte(evento.id(), evento.tipo()));
    }

    private static boolean contieneCoordenadas(Map<String, Object> datos) {
        return datos.get("coordenadas") != null
            || datos.get("latitud") != null
            || datos.get("longitud") != null;
    }

    private static UUID parseUuidObligatorio(Object valor, String campo) {
        if (!(valor instanceof String s) || s.isBlank()) {
            throw new EventoInvalidoException("Campo obligatorio inválido: " + campo);
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("UUID inválido en campo: " + campo, e);
        }
    }

    private static int parseVersion(Object valor) {
        if (valor instanceof Number n && n.intValue() >= 1) {
            return n.intValue();
        }
        throw new EventoInvalidoException("Versión de hallazgo inválida");
    }

    private static LocalDate parseFechaObligatoria(Object valor, String campo) {
        if (!(valor instanceof String s) || s.isBlank()) {
            throw new EventoInvalidoException("Fecha obligatoria faltante: " + campo);
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new EventoInvalidoException("Fecha ISO inválida en campo: " + campo, e);
        }
    }

    private static String textoObligatorio(Object valor, String campo, int max) {
        if (!(valor instanceof String s) || s.isBlank() || s.trim().length() > max) {
            throw new EventoInvalidoException("Campo de texto obligatorio inválido: " + campo);
        }
        return s.trim();
    }

    private static String textoOpcional(Object valor, int max) {
        if (valor == null) {
            return null;
        }
        if (!(valor instanceof String s)) {
            throw new EventoInvalidoException("Campo de texto opcional con tipo inválido");
        }
        String limpio = s.trim();
        if (limpio.isEmpty()) {
            return null;
        }
        return limpio.length() > max ? limpio.substring(0, max) : limpio;
    }
}
