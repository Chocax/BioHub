package co.edu.unbosque.biohub.gateway.domain;

/**
 * Una tesela del mapa en el esquema XYZ de OpenStreetMap: en el zoom {@code z} hay 2^z × 2^z teselas,
 * numeradas desde 0. Solo se crean teselas válidas, así que nunca se pide al origen algo fuera del mapa.
 */
public record Tesela(int z, int x, int y) {

    public Tesela {
        if (z < 0 || z > 30) {
            throw new IllegalArgumentException("zoom fuera de rango");
        }
        long lado = 1L << z;
        if (x < 0 || y < 0 || x >= lado || y >= lado) {
            throw new IllegalArgumentException("tesela fuera del mapa");
        }
    }

    /** Valida además el zoom máximo que sirve el gateway. */
    public static Tesela de(int z, int x, int y, int zoomMaximo) {
        if (z > zoomMaximo) {
            throw new IllegalArgumentException("zoom mayor que el permitido");
        }
        return new Tesela(z, x, y);
    }
}
