package co.edu.unbosque.biohub.gateway.domain;

/**
 * Servidor del que salen las teselas del mapa (Adapter). El servicio no sabe si es OpenStreetMap, un servidor
 * propio o un falso de prueba.
 *
 * <p>Si no hay conexión lanza {@code ResourceAccessException}. Si responde algo que no es una imagen válida lanza
 * {@code TeselaNoDisponibleException}.
 */
public interface OrigenTeselas {

    ImagenTesela descargar(Tesela tesela);
}
