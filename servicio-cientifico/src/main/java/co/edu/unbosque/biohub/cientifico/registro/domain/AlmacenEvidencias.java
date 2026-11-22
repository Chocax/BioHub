package co.edu.unbosque.biohub.cientifico.registro.domain;

import java.io.InputStream;
import java.util.UUID;

/**
 * Dónde viven los archivos de las evidencias (SAD §4.5: "volumen de archivos del Servicio Científico").
 * La base solo guarda metadatos y la ruta relativa que devuelve este puerto.
 */
public interface AlmacenEvidencias {

    /** Guarda el contenido y devuelve la ruta relativa con la que se vuelve a abrir. */
    String guardar(UUID hallazgoId, UUID evidenciaId, byte[] contenido);

    /**
     * Guarda la miniatura pública de una evidencia (D66) junto al original y devuelve su ruta relativa. Si ya había
     * una, la reemplaza.
     */
    String guardarMiniatura(UUID hallazgoId, UUID evidenciaId, byte[] contenido);

    /** Abre el archivo de una ruta devuelta por {@link #guardar}. */
    InputStream abrir(String ruta);

    /** Borra un archivo recién guardado cuando su registro en la base no se pudo confirmar. */
    void borrar(String ruta);
}
