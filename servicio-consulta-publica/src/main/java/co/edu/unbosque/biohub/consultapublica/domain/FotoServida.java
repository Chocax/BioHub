package co.edu.unbosque.biohub.consultapublica.domain;

import java.util.UUID;

/** Foto pública lista para entregar: el JPEG, su sha256 (es el ETag) y el id inmutable con que se pide. */
public record FotoServida(UUID fotoId, String sha256, byte[] bytes) {
}
