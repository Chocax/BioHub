package co.edu.unbosque.biohub.cientifico.registro.service;

/** Una clave de idempotencia se reutilizó con un cuerpo o usuario diferente. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("La clave de idempotencia ya fue usada con otra petición");
    }
}
