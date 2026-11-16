package co.edu.unbosque.biohub.cientifico.registro.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.edu.unbosque.biohub.cientifico.registro.domain.AlmacenEvidencias;

/**
 * Guarda las evidencias en una carpeta del servicio (Adapter del puerto {@link AlmacenEvidencias}).
 *
 * <p>La ruta se arma solo con UUID ({@code <hallazgo>/<evidencia>}), así que ningún dato del usuario llega al sistema
 * de archivos. Igual se comprueba que la ruta final quede dentro de la carpeta base. El archivo se escribe primero en
 * un temporal y luego se mueve, para que nunca quede uno a medias con el nombre definitivo.
 *
 * <p>En contenedores, {@code EVIDENCIAS_DIR} debe apuntar a un volumen persistente.
 */
@Component
public class AlmacenEvidenciasDisco implements AlmacenEvidencias {

    private final Path base;

    public AlmacenEvidenciasDisco(@Value("${biohub.registro.evidencias.directorio:./datos/evidencias}") String directorio) {
        this.base = Path.of(directorio).toAbsolutePath().normalize();
    }

    @Override
    public String guardar(UUID hallazgoId, UUID evidenciaId, byte[] contenido) {
        String relativa = hallazgoId + "/" + evidenciaId;
        Path destino = resolver(relativa);
        try {
            Files.createDirectories(destino.getParent());
            Path temporal = Files.createTempFile(destino.getParent(), ".subida-", ".tmp");
            try {
                Files.write(temporal, contenido);
                Files.move(temporal, destino, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporal);
            }
            return relativa;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la evidencia", e);
        }
    }

    @Override
    public InputStream abrir(String ruta) {
        try {
            return Files.newInputStream(resolver(ruta));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo abrir la evidencia", e);
        }
    }

    @Override
    public void borrar(String ruta) {
        try {
            Files.deleteIfExists(resolver(ruta));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar la evidencia", e);
        }
    }

    private Path resolver(String relativa) {
        Path ruta = base.resolve(relativa).normalize();
        if (!ruta.startsWith(base)) {
            throw new IllegalArgumentException("Ruta de evidencia fuera de la carpeta base");
        }
        return ruta;
    }
}
