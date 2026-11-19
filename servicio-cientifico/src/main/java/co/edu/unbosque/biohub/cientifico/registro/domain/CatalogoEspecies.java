package co.edu.unbosque.biohub.cientifico.registro.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lecturas agrupadas por especie para la galería de fichas y las listas desplegables de la web interna (D65).
 * Solo lectura; las especies salen de los hallazgos vigentes y de las fichas taxonómicas (una especie puede tener
 * ficha antes de su primer hallazgo).
 */
public interface CatalogoEspecies {

    long contar(String texto, String familia);

    List<Especie> buscar(String texto, String familia, int page, int size);

    Sugerencias sugerencias(int tope);

    /** Una especie con lo necesario para su tarjeta. {@code foto} es la fotografía más reciente (prefiere públicas). */
    record Especie(String nombreCientifico, String nombreComun, String familia, String estadoConservacion,
                   String categoriaUicn, long registros, List<String> departamentos, LocalDate ultimaRecoleccion,
                   Integer fichaVersion, Integer atributosFicha, Foto foto) {
    }

    record Foto(UUID hallazgoId, UUID evidenciaId, boolean publica) {
    }

    record EspecieConocida(String nombreCientifico, String nombreComun, String familia) {
    }

    record Sugerencias(List<EspecieConocida> especies, List<String> familias, List<String> investigadores) {
    }
}
