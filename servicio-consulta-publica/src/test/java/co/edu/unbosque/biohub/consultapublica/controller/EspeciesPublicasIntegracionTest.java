package co.edu.unbosque.biohub.consultapublica.controller;

import static org.hamcrest.Matchers.contains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;
import co.edu.unbosque.biohub.consultapublica.domain.EstadoConservacion;
import co.edu.unbosque.biohub.consultapublica.domain.FichaPublica;
import co.edu.unbosque.biohub.consultapublica.domain.FotoPublica;
import co.edu.unbosque.biohub.consultapublica.infrastructure.BusquedaEspeciesJdbc;
import co.edu.unbosque.biohub.consultapublica.service.CriterioBusquedaPublica;
import co.edu.unbosque.biohub.consultapublica.service.ProyectorFotos;
import co.edu.unbosque.biohub.consultapublica.soporte.Fotos;
import co.edu.unbosque.biohub.consultapublica.service.ProyectorFichas;
import co.edu.unbosque.biohub.consultapublica.soporte.BaseIntegracion;

/** D65: catálogo público por especie, familias y foto pública, contra MySQL 8.4 real. */
@Testcontainers(disabledWithoutDocker = true)
class EspeciesPublicasIntegracionTest extends BaseIntegracion {

    private static final byte[] JPEG = Fotos.jpeg(64, 48);

    @Autowired
    private ProyectorFichas proyector;
    @Autowired
    private ProyectorFotos fotos;
    @Autowired
    private BusquedaEspeciesJdbc repositorio;
    @Autowired
    private co.edu.unbosque.biohub.consultapublica.service.InvalidadorCacheFichas invalidador;

    private void foto(String semillaFoto, String semillaHallazgo, byte[] jpeg, Instant cambio) {
        fotos.publicar(new FotoPublica(id(semillaFoto), id(semillaHallazgo), Fotos.sha256(jpeg), 64, 48, jpeg, cambio));
    }

    private void ficha(String semilla, String cientifico, String comun, String familia, String deptoCodigo,
                       String deptoNombre, String region, EstadoConservacion estado, LocalDate fecha) {
        proyector.aplicar(new FichaPublica(id(semilla), 1, cientifico, comun, familia, "Adulto", fecha, estado,
                deptoCodigo, deptoNombre, region, "Descripción pública"));
    }

    @BeforeEach
    void datos() {
        ficha("ebenoides-1", "Atelopus ebenoides", "Rana arlequín", "Bufonidae", "15", "Boyacá", "Andina",
                EstadoConservacion.EN_PELIGRO, LocalDate.of(2026, 3, 14));
        ficha("ebenoides-2", "Atelopus ebenoides", "Rana arlequín de Boyacá", "Bufonidae", "25", "Cundinamarca", "Andina",
                EstadoConservacion.EN_PELIGRO, LocalDate.of(2026, 5, 2));
        ficha("macao", "Ara macao", "Guacamaya bandera", "Psittacidae", "50", "Meta", "Orinoquía",
                EstadoConservacion.NO_AMENAZADA, LocalDate.of(2026, 1, 9));
        ficha("espeletia", "Espeletia grandiflora", "Frailejón", "Asteraceae", "25", "Cundinamarca", "Andina",
                EstadoConservacion.SIN_EVALUAR, LocalDate.of(2026, 2, 1));
    }

    @Test
    void unaTarjetaPorEspecieConSusDepartamentosYElRegistroMasReciente() throws Exception {
        mvc.perform(get("/api/publica/especies"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=30"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.content[*].nombreCientifico",
                        contains("Ara macao", "Atelopus ebenoides", "Espeletia grandiflora")))
                .andExpect(jsonPath("$.content[1].registros").value(2))
                .andExpect(jsonPath("$.content[1].departamentos", contains("Boyacá", "Cundinamarca")))
                .andExpect(jsonPath("$.content[1].regiones", contains("Andina")))
                .andExpect(jsonPath("$.content[1].nombreComun").value("Rana arlequín de Boyacá"))
                .andExpect(jsonPath("$.content[1].fichaId").value(id("ebenoides-2").toString()))
                .andExpect(jsonPath("$.content[1].ultimaRecoleccion").value("2026-05-02"))
                .andExpect(jsonPath("$.content[1].investigador").doesNotExist())
                .andExpect(jsonPath("$.content[1].latitud").doesNotExist());
    }

    @Test
    void losFiltrosYLaPaginacionSonLosDeLasFichas() throws Exception {
        mvc.perform(get("/api/publica/especies").param("departamento", "Cundinamarca"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/publica/especies").param("familia", "Bufonidae"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/publica/especies").param("q", "guacamaya"))
                .andExpect(jsonPath("$.content[0].nombreCientifico").value("Ara macao"));
        mvc.perform(get("/api/publica/especies").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/publica/especies").param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lasFamiliasSalenOrdenadasYSinRepetir() throws Exception {
        mvc.perform(get("/api/publica/especies/familias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familias", contains("Asteraceae", "Bufonidae", "Psittacidae")));
    }

    @Test
    void laFotoPorIdSeSirveInmutableConEtagYSinFotoEs404() throws Exception {
        foto("foto-macao", "macao", JPEG, Instant.parse("2026-11-21T10:00:00Z"));
        String etag = "\"" + Fotos.sha256(JPEG) + "\"";

        mvc.perform(get("/api/publica/especies/fotos/" + id("foto-macao")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("ETag", etag))
                .andExpect(content().bytes(JPEG));
        // Revalidación: el mismo ETag responde 304 sin cuerpo y con las mismas cabeceras de caché
        mvc.perform(get("/api/publica/especies/fotos/" + id("foto-macao")).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));
        mvc.perform(get("/api/publica/especies/fotos/" + id("foto-macao")).header("If-None-Match", "\"otro\""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/publica/especies/fotos/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        mvc.perform(get("/api/publica/especies/fotos/no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"))
                .andExpect(jsonPath("$.errores[0].campo").value("fotoId"));
    }

    @Test
    void unaFotoRetiradaDejaDeServirse() throws Exception {
        foto("foto-macao", "macao", JPEG, Instant.parse("2026-11-21T10:00:00Z"));
        fotos.retirar(id("foto-macao"), id("macao"), Instant.parse("2026-11-21T11:00:00Z"));
        mvc.perform(get("/api/publica/especies/fotos/" + id("foto-macao"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/publica/especies/fotos/" + id("foto-macao")).header("If-None-Match", "*"))
                .andExpect(status().isNotFound());
    }

    @Test
    void laRutaPorNombreSirveLaFotoMasRecienteDeLaEspecieDesdeLaBaseDeLectura() throws Exception {
        byte[] reciente = Fotos.jpeg(32, 32);
        foto("foto-eb-1", "ebenoides-1", JPEG, Instant.parse("2026-11-21T10:00:00Z"));
        foto("foto-eb-2", "ebenoides-2", reciente, Instant.parse("2026-11-21T12:00:00Z"));

        mvc.perform(get("/api/publica/especies/foto").param("nombre", "atelopus EBENOIDES"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "public, max-age=300"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(reciente));
        mvc.perform(get("/api/publica/especies/foto").param("nombre", "Espeletia grandiflora"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/publica/especies/foto").param("nombre", "<script>alert(1)</script>"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/publica/especies/foto")).andExpect(status().isBadRequest());
    }

    @Test
    void elCatalogoTraeElFotoIdDeLaFotoPublicaMasRecienteOSinFotoNull() throws Exception {
        foto("foto-eb-1", "ebenoides-1", JPEG, Instant.parse("2026-11-21T10:00:00Z"));
        foto("foto-eb-2", "ebenoides-2", Fotos.jpeg(32, 32), Instant.parse("2026-11-21T12:00:00Z"));
        foto("foto-sin-ficha", "hallazgo-que-no-existe", JPEG, Instant.parse("2026-11-21T13:00:00Z"));
        fotos.publicar(new FotoPublica(id("foto-retirada"), id("macao"), Fotos.sha256(JPEG), 64, 48, JPEG,
                Instant.parse("2026-11-21T09:00:00Z")));
        fotos.retirar(id("foto-retirada"), id("macao"), Instant.parse("2026-11-21T09:30:00Z"));

        mvc.perform(get("/api/publica/especies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nombreCientifico").value("Ara macao"))
                .andExpect(jsonPath("$.content[0].fotoId", nullValue()))
                .andExpect(jsonPath("$.content[1].nombreCientifico").value("Atelopus ebenoides"))
                .andExpect(jsonPath("$.content[1].fotoId").value(id("foto-eb-2").toString()))
                .andExpect(jsonPath("$.content[2].fotoId", nullValue()));
    }

    @Test
    void unaFotoNuevaOUnaRetiradaCambianElCatalogoSinEsperarAlTtl() throws Exception {
        mvc.perform(get("/api/publica/especies")).andExpect(jsonPath("$.content[0].fotoId", nullValue()));
        assertThat(cacheManager.getCache("especies")).isNotNull();

        // El consumidor llama a este invalidador tras el commit; aquí se hace lo mismo a mano
        foto("foto-macao", "macao", JPEG, Instant.parse("2026-11-21T10:00:00Z"));
        invalidador.invalidarCatalogoPorFoto(id("foto-macao"));
        mvc.perform(get("/api/publica/especies"))
                .andExpect(jsonPath("$.content[0].fotoId").value(id("foto-macao").toString()));

        fotos.retirar(id("foto-macao"), id("macao"), Instant.parse("2026-11-21T11:00:00Z"));
        invalidador.invalidarCatalogoPorFoto(id("foto-macao"));
        mvc.perform(get("/api/publica/especies")).andExpect(jsonPath("$.content[0].fotoId", nullValue()));
    }

    @Test
    void enModoDegradadoElCatalogoOmiteLasFotos() {
        foto("foto-macao", "macao", JPEG, Instant.parse("2026-11-21T10:00:00Z"));
        CriterioBusquedaPublica normal = new CriterioBusquedaPublica(null, null, null, 0, 20, false, List.of());
        CriterioBusquedaPublica degradado = new CriterioBusquedaPublica(null, null, null, 0, 20, true, List.of());

        assertThat(repositorio.buscar(normal).get(0).fotoId()).isEqualTo(id("foto-macao"));
        assertThat(repositorio.buscar(degradado)).isNotEmpty().allSatisfy(e -> assertThat(e.fotoId()).isNull());
    }

    @Test
    void familiaVaciaSeIgnoraComoLosDemasParametrosVacios() throws Exception {
        mvc.perform(get("/api/publica/especies").param("familia", "")).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/publica/especies").param("familia", "   ")).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/publica/fichas").param("familia", "")).andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/publica/fichas").param("familia", " ")).andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/publica/fichas").param("familia", "x".repeat(101))).andExpect(status().isBadRequest());
    }
}
