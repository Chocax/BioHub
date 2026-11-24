package co.edu.unbosque.biohub.cientifico.registro.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unbosque.biohub.cientifico.eventos.domain.EventoSobre;
import co.edu.unbosque.biohub.cientifico.eventos.service.PublicadorEventos;
import co.edu.unbosque.biohub.cientifico.registro.domain.VersionHallazgoRepository;

/** Republica desde el snapshot inmutable del historial, nunca desde el estado actual del hallazgo. */
@Service
public class RepublicadorEventosHallazgoService {

    private final VersionHallazgoRepository versiones;
    private final PublicadorEventos publicador;

    public RepublicadorEventosHallazgoService(VersionHallazgoRepository versiones, PublicadorEventos publicador) {
        this.versiones = versiones;
        this.publicador = publicador;
    }

    @Transactional(readOnly = true)
    public EventoSobre republicar(java.util.UUID hallazgoId, int numeroVersion) {
        if (numeroVersion < 1) {
            throw new DatosHallazgoInvalidosException("version", "debe ser mayor o igual a 1");
        }
        var version = versiones.findByHallazgoIdAndVersion(hallazgoId, numeroVersion)
                .orElseThrow(() -> new HallazgoNoEncontradoException(hallazgoId));
        String tipo = numeroVersion == 1 ? "hallazgo.registrado" : "hallazgo.reclasificado";
        return publicador.publicar(tipo, version.getAutor(), version.getSnapshot());
    }
}
