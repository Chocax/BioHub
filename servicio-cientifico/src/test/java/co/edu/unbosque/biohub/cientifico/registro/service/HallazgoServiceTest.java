package co.edu.unbosque.biohub.cientifico.registro.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import co.edu.unbosque.biohub.cientifico.eventos.domain.HallazgoReclasificado;
import co.edu.unbosque.biohub.cientifico.eventos.domain.HallazgoRegistrado;
import co.edu.unbosque.biohub.cientifico.registro.domain.CambioAuditoria;
import co.edu.unbosque.biohub.cientifico.registro.domain.CambioAuditoriaRepository;
import co.edu.unbosque.biohub.cientifico.registro.domain.CategoriaUicn;
import co.edu.unbosque.biohub.cientifico.registro.domain.Hallazgo;
import co.edu.unbosque.biohub.cientifico.registro.domain.HallazgoRepository;
import co.edu.unbosque.biohub.cientifico.registro.domain.VersionHallazgo;
import co.edu.unbosque.biohub.cientifico.registro.domain.VersionHallazgoRepository;
import co.edu.unbosque.biohub.cientifico.registro.domain.EstadoConservacion;
import org.mockito.Mockito;

/** HU-01 y HU-03: qué se guarda, qué se publica y, sobre todo, qué nunca debe salir del módulo. */
class HallazgoServiceTest {

    private static final String INVESTIGADOR = "investigador1";
    private static final double LATITUD = 5.53214;
    private static final double LONGITUD = -73.36712;

    private HallazgoRepository hallazgos;
    private VersionHallazgoRepository versiones;
    private CambioAuditoriaRepository auditoria;
    private ApplicationEventPublisher publicador;
    private FichaPlantillaService plantillas;
    private ValidadorDepartamento departamentos;
    private HallazgoService servicio;

    @BeforeEach
    void prepararServicio() {
        hallazgos = Mockito.mock(HallazgoRepository.class);
        versiones = Mockito.mock(VersionHallazgoRepository.class);
        auditoria = Mockito.mock(CambioAuditoriaRepository.class);
        publicador = Mockito.mock(ApplicationEventPublisher.class);
        plantillas = Mockito.mock(FichaPlantillaService.class);
        departamentos = Mockito.mock(ValidadorDepartamento.class);
        when(departamentos.existeEnCatalogo(anyString())).thenReturn(true);
        ValidadorTextoPublico validador = new ValidadorTextoPublico();
        Clock reloj = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);

        when(hallazgos.saveAndFlush(any(Hallazgo.class))).thenAnswer(i -> i.getArgument(0));
        servicio = new HallazgoService(hallazgos, versiones, auditoria,
                new ConstructorSnapshotPublico(validador), validador, plantillas, departamentos, publicador,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), reloj);
    }

    private static RegistrarHallazgoComando comandoValido() {
        return comando("Boyaca", LATITUD, LONGITUD);
    }

    private static RegistrarHallazgoComando comando(String departamento, Double latitud, Double longitud) {
        return new RegistrarHallazgoComando("Atelopus ebenoides", "Rana arlequin", "Bufonidae", "Adulto",
                LocalDate.of(2026, 3, 14), departamento, CategoriaUicn.EN, "Anfibio endemico de paramo",
                latitud, longitud, null);
    }

    @Test
    @DisplayName("registrar guarda el hallazgo, su primera version y devuelve version 1")
    void registraYCreaHistorial() {
        ResultadoRegistro resultado = servicio.registrar(comandoValido(), INVESTIGADOR);

        assertThat(resultado.version()).isEqualTo(1);
        assertThat(resultado.hallazgoId()).isNotNull();
        verify(hallazgos).saveAndFlush(any(Hallazgo.class));

        ArgumentCaptor<VersionHallazgo> version = ArgumentCaptor.forClass(VersionHallazgo.class);
        verify(versiones).save(version.capture());
        assertThat(version.getValue().getVersion()).isEqualTo(1);
        assertThat(version.getValue().getAutor()).isEqualTo(INVESTIGADOR);
    }

    @Test
    @DisplayName("las coordenadas llegan a ubicacion pero NUNCA al evento publico (D14, RNF9)")
    void lasCoordenadasSoloVanAUbicacion() {
        servicio.registrar(comandoValido(), INVESTIGADOR);

        ArgumentCaptor<Object> eventos = ArgumentCaptor.forClass(Object.class);
        verify(publicador, Mockito.atLeast(2)).publishEvent(eventos.capture());

        HallazgoRegistrado paraUbicacion = (HallazgoRegistrado) eventos.getAllValues().stream()
                .filter(HallazgoRegistrado.class::isInstance).findFirst().orElseThrow();
        assertThat(paraUbicacion.latitud()).isEqualTo(LATITUD);
        assertThat(paraUbicacion.longitud()).isEqualTo(LONGITUD);

        CambioHallazgoConfirmado paraElBroker = (CambioHallazgoConfirmado) eventos.getAllValues().stream()
                .filter(CambioHallazgoConfirmado.class::isInstance).findFirst().orElseThrow();
        assertThat(paraElBroker.datos())
                .doesNotContainKeys("latitud", "longitud", "lat", "lon", "lng", "coordenadas", "investigador");
        assertThat(paraElBroker.datos().values().stream().map(String::valueOf))
                .noneMatch(v -> v.contains("5.53214") || v.contains("73.36712"));
    }

    @Test
    @DisplayName("el snapshot publico lleva exactamente los campos del contrato (§7.3)")
    void elSnapshotSigueElContrato() {
        servicio.registrar(comandoValido(), INVESTIGADOR);

        ArgumentCaptor<Object> eventos = ArgumentCaptor.forClass(Object.class);
        verify(publicador, Mockito.atLeast(2)).publishEvent(eventos.capture());
        CambioHallazgoConfirmado publico = (CambioHallazgoConfirmado) eventos.getAllValues().stream()
                .filter(CambioHallazgoConfirmado.class::isInstance).findFirst().orElseThrow();

        assertThat(publico.tipo()).isEqualTo("hallazgo.registrado");
        assertThat(publico.datos()).containsOnlyKeys("hallazgoId", "version", "nombreCientifico", "nombreComun",
                "familia", "tipoEspecimen", "fechaRecoleccion", "estadoConservacion", "departamento", "descripcion");
        assertThat(publico.datos().get("estadoConservacion")).isEqualTo("EN_PELIGRO");
        assertThat(publico.datos().get("fechaRecoleccion")).isEqualTo("2026-03-14");
    }

    @Test
    @DisplayName("una fecha de recoleccion futura se rechaza")
    void rechazaFechaFutura() {
        RegistrarHallazgoComando futuro = new RegistrarHallazgoComando("Atelopus ebenoides", null, "Bufonidae",
                "Adulto", LocalDate.of(2027, 1, 1), "Boyaca", CategoriaUicn.LC, null, null, null, null);

        assertThatThrownBy(() -> servicio.registrar(futuro, INVESTIGADOR))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("no futura");
        verify(hallazgos, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("faltan campos obligatorios: se deniega el registro (HU-01)")
    void exigeCamposObligatorios() {
        RegistrarHallazgoComando sinFamilia = new RegistrarHallazgoComando("Atelopus ebenoides", null, "  ",
                "Adulto", LocalDate.of(2026, 3, 14), "Boyaca", CategoriaUicn.LC, null, null, null, null);

        assertThatThrownBy(() -> servicio.registrar(sinFamilia, INVESTIGADOR))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .extracting(e -> ((DatosHallazgoInvalidosException) e).campo())
                .isEqualTo("familia");
    }

    @Test
    @DisplayName("valida el departamento antes de crear el hallazgo")
    void departamentoFueraDelCatalogoNoSeGuarda() {
        when(departamentos.existeEnCatalogo("Narnia")).thenReturn(false);

        assertThatThrownBy(() -> servicio.registrar(comando("Narnia", null, null), INVESTIGADOR))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .extracting(e -> ((DatosHallazgoInvalidosException) e).campo())
                .isEqualTo("departamento");
        verify(hallazgos, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("la captura de atributos se valida contra la plantilla antes de persistir")
    void validaAtributosAntesDeGuardar() {
        Map<String, Object> atributos = Map.of("desconocido", "valor");
        RegistrarHallazgoComando invalido = new RegistrarHallazgoComando("Atelopus ebenoides", null, "Bufonidae",
                "Adulto", LocalDate.of(2026, 3, 14), "Boyaca", CategoriaUicn.LC, null, null, null, atributos);
        when(plantillas.validarValores("Atelopus ebenoides", atributos))
                .thenThrow(new DatosHallazgoInvalidosException("atributos.desconocido", "atributo no definido"));

        assertThatThrownBy(() -> servicio.registrar(invalido, INVESTIGADOR))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("atributo no definido");
        verify(hallazgos, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("un reintento con la misma clave devuelve el hallazgo guardado sin duplicarlo")
    void reintentoEsIdempotente() {
        AtomicReference<Hallazgo> guardado = new AtomicReference<>();
        when(hallazgos.findByIdempotencyKey("solicitud-1"))
                .thenAnswer(i -> Optional.ofNullable(guardado.get()));
        when(hallazgos.saveAndFlush(any(Hallazgo.class))).thenAnswer(i -> {
            Hallazgo hallazgo = i.getArgument(0);
            guardado.set(hallazgo);
            return hallazgo;
        });

        ResultadoRegistro primero = servicio.registrar(comandoValido(), INVESTIGADOR, "solicitud-1");
        ResultadoRegistro segundo = servicio.registrar(comandoValido(), INVESTIGADOR, "solicitud-1");

        assertThat(segundo).isEqualTo(primero);
        verify(hallazgos).saveAndFlush(any(Hallazgo.class));
    }

    @Test
    @DisplayName("una clave de idempotencia no se puede reutilizar con otro contenido")
    void claveIdempotenciaConOtroContenidoDaConflicto() {
        AtomicReference<Hallazgo> guardado = new AtomicReference<>();
        when(hallazgos.findByIdempotencyKey("solicitud-1"))
                .thenAnswer(i -> Optional.ofNullable(guardado.get()));
        when(hallazgos.saveAndFlush(any(Hallazgo.class))).thenAnswer(i -> {
            Hallazgo hallazgo = i.getArgument(0);
            guardado.set(hallazgo);
            return hallazgo;
        });
        servicio.registrar(comandoValido(), INVESTIGADOR, "solicitud-1");

        assertThatThrownBy(() -> servicio.registrar(
                new RegistrarHallazgoComando("Atelopus muisca", "Rana", "Bufonidae", "Adulto",
                        LocalDate.of(2026, 3, 14), "Boyaca", CategoriaUicn.LC, null, null, null, null),
                INVESTIGADOR, "solicitud-1"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        verify(hallazgos).saveAndFlush(any(Hallazgo.class));
    }

    @Test
    @DisplayName("una descripcion con coordenadas escritas no se guarda (SEC-J-02)")
    void rechazaCoordenadasEnElTexto() {
        RegistrarHallazgoComando conCoordenadas = new RegistrarHallazgoComando("Atelopus ebenoides", null,
                "Bufonidae", "Adulto", LocalDate.of(2026, 3, 14), "Boyaca", CategoriaUicn.LC,
                "Encontrado en 5.53214, -73.36712", null, null, null);

        assertThatThrownBy(() -> servicio.registrar(conCoordenadas, INVESTIGADOR))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("coordenadas");
        verify(hallazgos, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("una coordenada sin su pareja se rechaza")
    void exigeAmbasCoordenadas() {
        RegistrarHallazgoComando soloLatitud = new RegistrarHallazgoComando("Atelopus ebenoides", null,
                "Bufonidae", "Adulto", LocalDate.of(2026, 3, 14), "Boyaca", CategoriaUicn.LC, null,
                LATITUD, null, null);

        assertThatThrownBy(() -> servicio.registrar(soloLatitud, INVESTIGADOR))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .hasMessageContaining("van juntas");
    }

    @Test
    @DisplayName("reclasificar sin justificacion cientifica se rechaza (HU-03)")
    void reclasificarExigeJustificacion() {
        UUID id = UUID.randomUUID();
        ReclasificarHallazgoComando sinJustificacion = new ReclasificarHallazgoComando(null, null, null, null,
                null, null, CategoriaUicn.CR, null, null, "   ");

        assertThatThrownBy(() -> servicio.reclasificar(id, sinJustificacion, "cientifico1"))
                .isInstanceOf(DatosHallazgoInvalidosException.class)
                .extracting(e -> ((DatosHallazgoInvalidosException) e).campo())
                .isEqualTo("justificacion");
    }

    @Test
    @DisplayName("reclasificar sube la version, deja auditoria del campo que cambio y avisa a ubicacion")
    void reclasificaYAudita() {
        UUID id = UUID.randomUUID();
        Hallazgo existente = new Hallazgo(id, "Atelopus ebenoides", null, "Bufonidae", "Adulto",
                LocalDate.of(2026, 3, 14), INVESTIGADOR, "Boyaca", EstadoConservacion.NO_AMENAZADA,
                CategoriaUicn.LC, null, null, null);
        when(hallazgos.buscarVigente(id)).thenReturn(Optional.of(existente));

        ReclasificarHallazgoComando comando = new ReclasificarHallazgoComando(null, null, null, null, null,
                null, CategoriaUicn.CR, null, null, "Pruebas de laboratorio confirman la especie");

        ResultadoRegistro resultado = servicio.reclasificar(id, comando, "cientifico1");

        assertThat(resultado.version()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CambioAuditoria>> cambios = ArgumentCaptor.forClass(List.class);
        verify(auditoria).saveAll(cambios.capture());
        assertThat(cambios.getValue()).extracting(CambioAuditoria::getCampo)
                .contains("estadoConservacion", "categoriaUicn");
        assertThat(cambios.getValue()).allSatisfy(c -> {
            assertThat(c.getJustificacion()).isEqualTo("Pruebas de laboratorio confirman la especie");
            assertThat(c.getAutor()).isEqualTo("cientifico1");
            assertThat(c.getVersionNueva()).isEqualTo(2);
        });

        ArgumentCaptor<Object> eventos = ArgumentCaptor.forClass(Object.class);
        verify(publicador, Mockito.atLeast(2)).publishEvent(eventos.capture());
        HallazgoReclasificado aviso = (HallazgoReclasificado) eventos.getAllValues().stream()
                .filter(HallazgoReclasificado.class::isInstance).findFirst().orElseThrow();
        assertThat(aviso.version()).isEqualTo(2);
        assertThat(aviso.estadoConservacion()).isEqualTo("EN_PELIGRO");
    }

    @Test
    @DisplayName("reclasificar un hallazgo que no existe da 404 y no publica nada")
    void reclasificarHallazgoInexistente() {
        UUID id = UUID.randomUUID();
        when(hallazgos.buscarVigente(id)).thenReturn(Optional.empty());
        ReclasificarHallazgoComando comando = new ReclasificarHallazgoComando(null, null, null, null, null,
                null, CategoriaUicn.CR, null, null, "Justificacion valida");

        assertThatThrownBy(() -> servicio.reclasificar(id, comando, "cientifico1"))
                .isInstanceOf(HallazgoNoEncontradoException.class);
        verify(publicador, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("el comando de registro nunca imprime las coordenadas en su toString")
    void elComandoNoFiltraCoordenadasEnLogs() {
        assertThat(comandoValido().toString())
                .contains("coordenadas=***")
                .doesNotContain("5.53214")
                .doesNotContain("73.36712");
    }
}
