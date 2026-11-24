package co.edu.unbosque.biohub.cientifico.registro.service;

/** Puerto para validar el código o nombre de departamento contra el catálogo oficial. */
public interface ValidadorDepartamento {

    boolean existeEnCatalogo(String departamento);
}
