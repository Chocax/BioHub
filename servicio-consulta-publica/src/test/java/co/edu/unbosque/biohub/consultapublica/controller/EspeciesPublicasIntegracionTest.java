package co.edu.unbosque.biohub.consultapublica.controller;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import co.edu.unbosque.biohub.consultapublica.domain.EstadoConservacion;
import co.edu.unbosque.biohub.consultapublica.domain.FichaPublica;
import co.edu.unbosque.biohub.consultapublica.infrastructure.FotosCientificoHttp;
import co.edu.unbosque.biohub.consultapublica.service.ProyectorFichas;
import co.edu.unbosque.biohub.consultapublica.soporte.BaseIntegracion;

/** D65: catálogo público por especie, familias y foto pública, contra MySQL 8.4 real. */
@Testcontainers(disabledWithoutDocker = true)
class EspeciesPublicasIntegracionTest extends BaseIntegracion {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    @Autowired
    private ProyectorFichas proyector;
    @MockitoBean
    private FotosCientificoHttp cientifico;

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
    void laFotoPublicaSeSirveComoJpegConCacheYSinFotoEs404() throws Exception {
        when(cientifico.fotoPublica(anyString())).thenReturn(Optional.empty());
        when(cientifico.fotoPublica(eq("Ara macao"))).thenReturn(Optional.of(JPEG));

        mvc.perform(get("/api/publica/especies/foto").param("nombre", "Ara macao"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=600")))
                .andExpect(content().bytes(JPEG));
        mvc.perform(get("/api/publica/especies/foto").param("nombre", "Espeletia grandiflora"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/publica/especies/foto").param("nombre", "<script>alert(1)</script>"))
                .andExpect(status().isBadRequest());
    }
}
