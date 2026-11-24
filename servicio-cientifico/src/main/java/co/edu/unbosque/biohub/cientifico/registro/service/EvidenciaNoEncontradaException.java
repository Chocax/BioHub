package co.edu.unbosque.biohub.cientifico.registro.service;

/** La evidencia no existe o no pertenece al hallazgo indicado (404). */
public class EvidenciaNoEncontradaException extends RuntimeException {

    public EvidenciaNoEncontradaException() {
        super("La evidencia no existe en este hallazgo.");
    }
}
