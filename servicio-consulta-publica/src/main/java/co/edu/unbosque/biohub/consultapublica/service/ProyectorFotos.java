package co.edu.unbosque.biohub.consultapublica.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unbosque.biohub.consultapublica.domain.FotoPublica;
import co.edu.unbosque.biohub.consultapublica.domain.ResultadoProyeccion;
import co.edu.unbosque.biohub.consultapublica.infrastructure.FotosPublicasJdbc;

/**
 * Aplica un evento de foto al modelo de lectura (D66, contrato 04 §7.6). Gana el {@code cambioEn} mayor: un evento
 * repetido o desordenado no cambia nada (es una sola sentencia {@code UPDATE ... WHERE cambio_en < ?}). Una
 * retirada que llega antes que la publicación deja una marca, para que la publicación anterior se ignore. La
 * invalidación de la caché la hace quien llama, DESPUÉS del commit.
 */
@Service
public class ProyectorFotos {

    private final FotosPublicasJdbc repositorio;

    public ProyectorFotos(FotosPublicasJdbc repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional
    public ResultadoProyeccion publicar(FotoPublica foto) {
        if (repositorio.actualizarPublicada(foto) > 0) {
            return ResultadoProyeccion.ACTUALIZADA;
        }
        try {
            repositorio.insertarPublicada(foto);
            return ResultadoProyeccion.CREADA;
        } catch (DuplicateKeyException existe) {
            // Ya existe con un cambio igual o posterior (o lo insertó otro consumidor entre medias): se reintenta una vez
            return repositorio.actualizarPublicada(foto) > 0 ? ResultadoProyeccion.ACTUALIZADA : ResultadoProyeccion.IGNORADA;
        }
    }

    @Transactional
    public ResultadoProyeccion retirar(UUID evidenciaId, UUID hallazgoId, Instant cambioEn) {
        if (repositorio.actualizarRetirada(evidenciaId, hallazgoId, cambioEn) > 0) {
            return ResultadoProyeccion.ACTUALIZADA;
        }
        try {
            repositorio.insertarRetirada(evidenciaId, hallazgoId, cambioEn);
            return ResultadoProyeccion.CREADA;
        } catch (DuplicateKeyException existe) {
            return repositorio.actualizarRetirada(evidenciaId, hallazgoId, cambioEn) > 0
                    ? ResultadoProyeccion.ACTUALIZADA : ResultadoProyeccion.IGNORADA;
        }
    }
}
