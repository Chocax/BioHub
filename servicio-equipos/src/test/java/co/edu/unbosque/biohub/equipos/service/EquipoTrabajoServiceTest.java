package co.edu.unbosque.biohub.equipos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajo;
import co.edu.unbosque.biohub.equipos.domain.EquipoTrabajoRepository;
import co.edu.unbosque.biohub.equipos.domain.EstadoEquipo;
import co.edu.unbosque.biohub.equipos.domain.ValidadorCustodioPort;
import co.edu.unbosque.biohub.equipos.domain.ValidadorCustodioPort.CustodioValidado;
import co.edu.unbosque.biohub.equipos.infrastructure.GatewayCustodioAdapter.CustodioInvalidoException;
import co.edu.unbosque.biohub.equipos.service.EquipoTrabajoService.ConflictoEquipoException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EquipoTrabajoServiceTest {

    private EquipoTrabajoRepository repository;
    private ValidadorCustodioPort validadorCustodio;
    private EquipoTrabajoService service;

    @BeforeEach
    void setUp() {
        repository = mock(EquipoTrabajoRepository.class);
        validadorCustodio = mock(ValidadorCustodioPort.class);
        service = new EquipoTrabajoService(repository, validadorCustodio);
        when(repository.save(any(EquipoTrabajo.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registrarEquipoNormalizaCodigoYLoCreaDisponible() {
        when(repository.existsByCodigoInventarioIgnoreCase("CAM-001")).thenReturn(false);

        EquipoTrabajo creado = service.registrar(" cam-001 ", "Cámara Trampa Bushnell", "FOTOTRAMPEO", "Sensor IR");

        assertNotNull(creado.getId());
        assertEquals("CAM-001", creado.getCodigoInventario());
        assertEquals(EstadoEquipo.DISPONIBLE, creado.getEstado());
        assertNull(creado.getCustodioId());
    }

    @Test
    void registrarCodigoDuplicadoLanzaConflicto() {
        when(repository.existsByCodigoInventarioIgnoreCase("GPS-10")).thenReturn(true);

        assertThrows(ConflictoEquipoException.class,
            () -> service.registrar("gps-10", "GPS Garmin", "GEOLOCALIZACION", null));
    }

    @Test
    void asignarACustodioActivoActualizaEstadoYCustodio() {
        UUID id = UUID.randomUUID();
        EquipoTrabajo equipo = new EquipoTrabajo(id, "BIO-02", "Grabadora Acústica", "AUDIO", null);
        when(repository.findById(id)).thenReturn(Optional.of(equipo));
        when(validadorCustodio.validarCustodioActivo("kc-user-1", "Bearer token-admin"))
            .thenReturn(new CustodioValidado("kc-user-1", "investigador1", true));

        EquipoTrabajo asignado = service.asignar(id, "kc-user-1", "Bearer token-admin");

        assertEquals(EstadoEquipo.ASIGNADO, asignado.getEstado());
        assertEquals("kc-user-1", asignado.getCustodioId());
        assertEquals("investigador1", asignado.getCustodioUsuario());
        assertNotNull(asignado.getAsignadoEn());
    }

    @Test
    void asignarACustodioInactivoFalla() {
        UUID id = UUID.randomUUID();
        EquipoTrabajo equipo = new EquipoTrabajo(id, "BIO-03", "Red de Niebla", "MUESTREO", null);
        when(repository.findById(id)).thenReturn(Optional.of(equipo));
        when(validadorCustodio.validarCustodioActivo("kc-inactivo", "Bearer token-admin"))
            .thenReturn(new CustodioValidado("kc-inactivo", "exempleado", false));

        assertThrows(CustodioInvalidoException.class,
            () -> service.asignar(id, "kc-inactivo", "Bearer token-admin"));
    }

    @Test
    void devolverEquipoLiberaCustodioYDejaDisponible() {
        UUID id = UUID.randomUUID();
        EquipoTrabajo equipo = new EquipoTrabajo(id, "BIO-04", "Binoculares", "OPTICA", null);
        when(repository.findById(id)).thenReturn(Optional.of(equipo));
        when(validadorCustodio.validarCustodioActivo("kc-user-1", "Bearer t"))
            .thenReturn(new CustodioValidado("kc-user-1", "investigador1", true));

        service.asignar(id, "kc-user-1", "Bearer t");
        EquipoTrabajo devuelto = service.devolverOCambiarEstado(id, EstadoEquipo.DISPONIBLE);

        assertEquals(EstadoEquipo.DISPONIBLE, devuelto.getEstado());
        assertNull(devuelto.getCustodioId());
        assertNull(devuelto.getAsignadoEn());
    }
}
