package co.edu.unbosque.biohub.gateway.service;

/** El servidor de teselas respondió, pero no con una imagen válida (estado, tipo o tamaño): 502. */
public class TeselaNoDisponibleException extends RuntimeException {

    public TeselaNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
