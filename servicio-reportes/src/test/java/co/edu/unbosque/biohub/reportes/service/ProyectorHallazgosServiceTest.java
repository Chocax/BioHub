package co.edu.unbosque.biohub.reportes.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.biohub.reportes.domain.EventoProcesadoReporteRepository;
import co.edu.unbosque.biohub.reportes.domain.HallazgoReporte;
import co.edu.unbosque.biohub.reportes.domain.HallazgoReporteRepository;
import co.edu.unbosque.biohub.reportes.eventos.domain.EventoSobre;
import co.edu.unbosque.biohub.reportes.eventos.service.EventoInvalidoException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProyectorHallazgosServiceTest {

    private HallazgoReporteRepository hallazgoRepo;
    private EventoProcesadoReporteRepository eventoRepo;
    private ProyectorHallazgosService proyector;

    @BeforeEach
    void setUp() {
        hallazgoRepo = mock(HallazgoReporteRepository.class);
        eventoRepo = mock(EventoProcesadoReporteRepository.class);
        proyector = new ProyectorHallazgosService(hallazgoRepo, eventoRepo);
    }

    @Test
    void proyectaHallazgoRegistradoYGuardaEventoProcesado() {
        UUID eventoId = UUID.randomUUID();
        UUID hallazgoId = UUID.randomUUID();
        when(eventoRepo.existsById(eventoId)).thenReturn(false);
        when(hallazgoRepo.findById(hallazgoId)).thenReturn(Optional.empty());

        EventoSobre evento = new EventoSobre(
            eventoId, "hallazgo.registrado", "servicio-cientifico", Instant.now(), "investigador1",
            Map.of(
                "hallazgoId", hallazgoId.toString(),
                "version", 1,
                "nombreCientifico", "Atelopus ebenoides",
                "familia", "Bufonidae",
                "fechaRecoleccion", "2026-10-15",
                "estadoConservacion", "EN_PELIGRO",
                "departamento", "Boyacá"
            ));

        proyector.proyectar(evento);

        ArgumentCaptor<HallazgoReporte> captor = ArgumentCaptor.forClass(HallazgoReporte.class);
        verify(hallazgoRepo).save(captor.capture());
        assertEquals("Atelopus ebenoides", captor.getValue().getNombreCientifico());
        assertEquals("Boyacá", captor.getValue().getDepartamento());
        verify(eventoRepo).save(any());
    }

    @Test
    void rechazaEventoEnPeligroQueIncluyaCoordenadas() {
        UUID eventoId = UUID.randomUUID();
        when(eventoRepo.existsById(eventoId)).thenReturn(false);

        EventoSobre eventoConFuga = new EventoSobre(
            eventoId, "hallazgo.registrado", "servicio-cientifico", Instant.now(), "investigador1",
            Map.of(
                "hallazgoId", UUID.randomUUID().toString(),
                "version", 1,
                "nombreCientifico", "Atelopus ebenoides",
                "familia", "Bufonidae",
                "fechaRecoleccion", "2026-10-15",
                "estadoConservacion", "EN_PELIGRO",
                "departamento", "Boyacá",
                "coordenadas", Map.of("latitud", 5.5, "longitud", -73.3)
            ));

        assertThrows(EventoInvalidoException.class, () -> proyector.proyectar(eventoConFuga));
        verify(hallazgoRepo, never()).save(any());
    }

    @Test
    void ignoraEventoDuplicadoPorEventoId() {
        UUID eventoId = UUID.randomUUID();
        when(eventoRepo.existsById(eventoId)).thenReturn(true);

        EventoSobre duplicado = new EventoSobre(
            eventoId, "hallazgo.registrado", "servicio-cientifico", Instant.now(), "investigador1", Map.of());

        proyector.proyectar(duplicado);

        verify(hallazgoRepo, never()).save(any());
        verify(eventoRepo, never()).save(any());
    }

    @Test
    void ignoraReclasificacionConVersionMenorOIgual() {
        UUID eventoId = UUID.randomUUID();
        UUID hallazgoId = UUID.randomUUID();
        HallazgoReporte actual = new HallazgoReporte(
            hallazgoId, 3, "Tremarctos ornatus", "Oso de anteojos", "Ursidae",
            "AVISTAMIENTO", LocalDate.of(2026, 9, 10), "EN_PELIGRO", "Cundinamarca", "Andina");

        when(eventoRepo.existsById(eventoId)).thenReturn(false);
        when(hallazgoRepo.findById(hallazgoId)).thenReturn(Optional.of(actual));

        EventoSobre atrasado = new EventoSobre(
            eventoId, "hallazgo.reclasificado", "servicio-cientifico", Instant.now(), "auditor1",
            Map.of(
                "hallazgoId", hallazgoId.toString(),
                "version", 2,
                "nombreCientifico", "Nombre Viejo",
                "familia", "Ursidae",
                "fechaRecoleccion", "2026-09-10",
                "estadoConservacion", "NO_AMENAZADA",
                "departamento", "Cundinamarca"
            ));

        proyector.proyectar(atrasado);

        assertEquals(3, actual.getVersion());
        assertEquals("Tremarctos ornatus", actual.getNombreCientifico());
        assertEquals("EN_PELIGRO", actual.getEstadoConservacion());
    }
}
