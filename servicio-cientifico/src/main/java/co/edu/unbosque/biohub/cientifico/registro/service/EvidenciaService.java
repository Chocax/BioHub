package co.edu.unbosque.biohub.cientifico.registro.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.data.domain.PageRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import co.edu.unbosque.biohub.cientifico.registro.domain.AlmacenEvidencias;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgoRepository;
import co.edu.unbosque.biohub.cientifico.registro.domain.HallazgoRepository;

/**
 * Evidencias de un hallazgo: fotografías y grabaciones de audio (RF1, HU-01 c.2).
 *
 * <p><b>Reglas:</b>
 * <ul>
 *   <li>El hallazgo debe existir. Las evidencias se agregan, nunca se reemplazan ni se borran (como el historial).</li>
 *   <li>El formato se reconoce por el contenido ({@link TipoArchivoEvidencia}), no por la extensión.</li>
 *   <li>A las fotos y a los audios se les quitan los metadatos antes de guardarlos: pueden traer las coordenadas
 *       GPS ({@link LimpiadorMetadatos}, RNF9).</li>
 *   <li>Subir dos veces el mismo archivo al mismo hallazgo devuelve la evidencia que ya existe (UNIQUE por SHA-256).</li>
 *   <li>El archivo se guarda en disco con un nombre propio ({@code <hallazgo>/<evidencia>}): el nombre que trae el
 *       usuario solo se guarda como texto para mostrarlo, nunca se usa como ruta.</li>
 * </ul>
 */
@Service
public class EvidenciaService {

    private static final Logger log = LoggerFactory.getLogger(EvidenciaService.class);
    private static final int MAX_NOMBRE = 255;

    private final HallazgoRepository hallazgos;
    private final EvidenciaHallazgoRepository evidencias;
    private final AlmacenEvidencias almacen;
    private final long tamanoMaximo;

    public EvidenciaService(HallazgoRepository hallazgos, EvidenciaHallazgoRepository evidencias,
                            AlmacenEvidencias almacen,
                            @Value("${biohub.registro.evidencias.tamano-maximo:9MB}") DataSize tamanoMaximo) {
        this.hallazgos = hallazgos;
        this.evidencias = evidencias;
        this.almacen = almacen;
        this.tamanoMaximo = tamanoMaximo.toBytes();
    }

    /** Resultado de una subida: {@code nueva} es falso si el mismo archivo ya estaba. */
    public record Subida(EvidenciaHallazgo evidencia, boolean nueva) {
    }

    @Transactional
    public Subida subir(UUID hallazgoId, String nombreOriginal, byte[] contenido, String usuario) {
        if (!hallazgos.existsById(hallazgoId)) {
            throw new HallazgoNoEncontradoException(hallazgoId);
        }
        if (contenido == null || contenido.length == 0) {
            throw new DatosHallazgoInvalidosException("archivo", "El archivo está vacío.");
        }
        if (contenido.length > tamanoMaximo) {
            throw new DatosHallazgoInvalidosException("archivo",
                    "El archivo supera el máximo de " + DataSize.ofBytes(tamanoMaximo).toMegabytes() + " MB.");
        }
        TipoArchivoEvidencia formato = TipoArchivoEvidencia.detectar(contenido)
                .orElseThrow(() -> new DatosHallazgoInvalidosException("archivo",
                        "Formato no admitido. Usa fotografías JPEG o PNG, o audio WAV, MP3, OGG o FLAC."));
        byte[] limpio = LimpiadorMetadatos.limpiar(formato, contenido);
        String sha = sha256(limpio);

        Optional<EvidenciaHallazgo> repetida = evidencias.findByHallazgoIdAndSha256(hallazgoId, sha);
        if (repetida.isPresent()) {
            return new Subida(repetida.get(), false);
        }

        UUID evidenciaId = UUID.randomUUID();
        String ruta = almacen.guardar(hallazgoId, evidenciaId, limpio);
        try {
            EvidenciaHallazgo guardada = evidencias.saveAndFlush(new EvidenciaHallazgo(evidenciaId, hallazgoId,
                    nombreVisible(nombreOriginal, formato), formato.tipo(), formato.contentType(), limpio.length,
                    ruta, sha, usuario));
            return new Subida(guardada, true);
        } catch (RuntimeException e) {
            // Sin fila en la base, el archivo quedaría huérfano en disco.
            almacen.borrar(ruta);
            log.warn("No se pudo registrar la evidencia del hallazgo {}; se borró el archivo", hallazgoId);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<EvidenciaHallazgo> listar(UUID hallazgoId) {
        if (!hallazgos.existsById(hallazgoId)) {
            throw new HallazgoNoEncontradoException(hallazgoId);
        }
        return evidencias.findByHallazgoIdOrderByCreadoEnAsc(hallazgoId);
    }

    /** Metadatos de una evidencia que pertenece al hallazgo; si no, 404 (no se puede pedir la de otro). */
    @Transactional(readOnly = true)
    public EvidenciaHallazgo obtener(UUID hallazgoId, UUID evidenciaId) {
        return evidencias.findById(evidenciaId)
                .filter(e -> e.getHallazgoId().equals(hallazgoId))
                .orElseThrow(EvidenciaNoEncontradaException::new);
    }

    public InputStream abrir(EvidenciaHallazgo evidencia) {
        return almacen.abrir(evidencia.getRuta());
    }

    /**
     * Marca o desmarca una fotografía como visible en el portal público (D65). La decide quien registra: un
     * audio nunca es público. Queda en el log quién lo hizo (HU-11 c.3).
     */
    @Transactional
    public EvidenciaHallazgo marcarPublica(UUID hallazgoId, UUID evidenciaId, boolean publica, String usuario) {
        EvidenciaHallazgo evidencia = obtener(hallazgoId, evidenciaId);
        if (publica && evidencia.getTipo() != EvidenciaHallazgo.TipoEvidencia.FOTOGRAFIA) {
            throw new DatosHallazgoInvalidosException("publica", "Solo una fotografía se puede mostrar en el portal público.");
        }
        evidencia.marcarPublica(publica);
        log.info("Evidencia {} del hallazgo {} {} el portal público por {}", evidenciaId, hallazgoId,
                publica ? "visible en" : "retirada de", usuario);
        return evidencias.save(evidencia);
    }

    /**
     * La foto pública más reciente de la especie, reducida a {@link FotoPublica#LADO_MAXIMO} px y en JPEG (D65):
     * el portal no descarga archivos de varios MB y nunca recibe el original. Ya no trae metadatos (se limpiaron
     * al subirla).
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> fotoPublicaDeEspecie(String nombreCientifico) {
        return evidencias.fotosPublicasDeEspecie(nombreCientifico.trim(), PageRequest.of(0, 1)).stream()
                .findFirst()
                .flatMap(e -> {
                    try (InputStream datos = almacen.abrir(e.getRuta())) {
                        return FotoPublica.reducir(datos.readAllBytes());
                    } catch (IOException | UncheckedIOException ex) {
                        log.warn("No se pudo leer la foto pública {}: {}", e.getId(), ex.getClass().getSimpleName());
                        return Optional.empty();
                    }
                });
    }

    /** Nombre para mostrar: sin carpetas ni caracteres de control, y con la extensión del formato real. */
    static String nombreVisible(String original, TipoArchivoEvidencia formato) {
        String base = original == null ? "" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "").strip();
        int punto = base.lastIndexOf('.');
        if (punto > 0) {
            base = base.substring(0, punto);
        }
        if (base.isBlank()) {
            base = formato.tipo().name().toLowerCase();
        }
        String extension = "." + formato.name().toLowerCase();
        int maximo = MAX_NOMBRE - extension.length();
        return (base.length() > maximo ? base.substring(0, maximo) : base) + extension;
    }

    private static String sha256(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
