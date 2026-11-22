package co.edu.unbosque.biohub.cientifico.registro.service;

/** Ya hay una republicación de fotos públicas en curso (409). */
public class RepublicacionEnCursoException extends RuntimeException {

    public RepublicacionEnCursoException() {
        super("Ya hay una republicación de fotos públicas en curso.");
    }
}
