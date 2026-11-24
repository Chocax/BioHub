package co.edu.unbosque.biohub.reportes.domain;

/** Agregado por departamento que calcula la base analítica (nunca se cargan las filas una por una). */
public record ConteoDepartamento(String departamento, Long hallazgos, Long enPeligro, Long especies) {

    public long hallazgosOCero() {
        return hallazgos == null ? 0 : hallazgos;
    }

    public long enPeligroOCero() {
        return enPeligro == null ? 0 : enPeligro;
    }

    public long especiesOCero() {
        return especies == null ? 0 : especies;
    }
}
