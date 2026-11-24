package co.edu.unbosque.biohub.consultapublica.service;

/** La foto pedida no existe o ya no es pública: se traduce a 404 {@code NO_ENCONTRADO}. */
public class FotoNoEncontradaException extends RuntimeException {

    public FotoNoEncontradaException() {
        super("La foto solicitada no existe.");
    }
}
