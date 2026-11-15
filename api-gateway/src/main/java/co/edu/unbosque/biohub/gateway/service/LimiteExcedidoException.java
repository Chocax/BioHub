package co.edu.unbosque.biohub.gateway.service;

/** Se superó un límite de peticiones dentro de un controlador: 429 con Retry-After. */
public class LimiteExcedidoException extends RuntimeException {

    private final long segundosParaReintentar;

    public LimiteExcedidoException(long segundosParaReintentar) {
        super("Límite de peticiones superado");
        this.segundosParaReintentar = segundosParaReintentar;
    }

    public long segundosParaReintentar() {
        return segundosParaReintentar;
    }
}
