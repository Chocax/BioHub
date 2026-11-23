package co.edu.unbosque.biohub.consultapublica.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unbosque.biohub.consultapublica.domain.FotoServida;
import co.edu.unbosque.biohub.consultapublica.infrastructure.FotosPublicasJdbc;

/**
 * Fotos públicas de las especies (D66). Salen de {@code foto_publica} en la base de lectura: este servicio ya no le
 * pide nada al Servicio Científico. La URL por {@code fotoId} es inmutable (el navegador la guarda un año) y lleva el
 * sha256 como ETag; por eso aquí no hay una caché propia de bytes: una caché por réplica podría seguir sirviendo una
 * foto ya retirada (el evento de retirada lo procesa una sola réplica) y el navegador ya evita la mayoría de las
 * lecturas. Si la medición de la portada fría lo pide, la caché de bytes debe ir en Redis.
 */
@Service
public class FotosEspeciesService {

    private final FotosPublicasJdbc repositorio;

    public FotosEspeciesService(FotosPublicasJdbc repositorio) {
        this.repositorio = repositorio;
    }

    /** El sha256 (ETag) de la foto pública, sin leer los bytes (permite responder 304). */
    public Optional<String> sha256(UUID fotoId) {
        return repositorio.shaPublica(fotoId);
    }

    /** La foto pública por su id; vacío si no existe o ya no es pública. */
    public Optional<FotoServida> porId(UUID fotoId) {
        return repositorio.publicaPorId(fotoId);
    }

    /** La foto pública más reciente de una especie (ruta de compatibilidad {@code ?nombre=}). */
    public Optional<FotoServida> porNombre(String nombreCientifico) {
        return repositorio.masRecientePorEspecie(nombreCientifico.trim());
    }
}
