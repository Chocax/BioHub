package co.edu.unbosque.biohub.gateway.domain;

/** Imagen de una tesela tal como la entregó el servidor de origen. */
public record ImagenTesela(byte[] bytes, String tipoContenido) {
}
