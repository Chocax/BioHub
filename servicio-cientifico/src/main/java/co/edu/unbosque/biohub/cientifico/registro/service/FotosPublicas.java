package co.edu.unbosque.biohub.cientifico.registro.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unbosque.biohub.cientifico.registro.domain.AlmacenEvidencias;
import co.edu.unbosque.biohub.cientifico.registro.domain.EvidenciaHallazgo;

/**
 * Lado productor de las fotos públicas (D66, 04 §7.6): genera <b>una sola vez</b> la miniatura de una foto que pasa a
 * ser pública, la guarda junto al original y anuncia {@code evidencia.publicada} / {@code evidencia.retirada}.
 *
 * <p>No publica nada en el broker: dentro de la transacción en curso emite un {@link CambioHallazgoConfirmado}, y
 * el listener de infraestructura lo envía <b>después del commit</b> (mismo camino que {@code hallazgo.*}). Por eso
 * todos los métodos exigen una transacción activa ({@link Propagation#MANDATORY}).
 *
 * <p>La miniatura no lleva metadatos y las coordenadas nunca viajan: el evento solo trae ids, un hash, dimensiones y
 * los bytes del JPEG.
 */
@Component
public class FotosPublicas {

    private static final Logger log = LoggerFactory.getLogger(FotosPublicas.class);

    private final AlmacenEvidencias almacen;
    private final ApplicationEventPublisher publicador;
    private final Clock reloj;

    public FotosPublicas(AlmacenEvidencias almacen, ApplicationEventPublisher publicador, Clock reloj) {
        this.almacen = almacen;
        this.publicador = publicador;
        this.reloj = reloj;
    }

    /** Instante actual a microsegundos, como lo guarda la columna {@code DATETIME(6)}. */
    public Instant ahora() {
        return Instant.now(reloj).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Anuncia que la foto es pública. Genera la miniatura si no existe (una sola vez: si ya hay una en disco se
     * reutiliza). El cambio de visibilidad (la columna {@code publica}) ya lo fijó quien llama.
     *
     * @throws DatosHallazgoInvalidosException (400) si la imagen no se puede leer o pasa de 40 MP
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void anunciarPublicada(EvidenciaHallazgo evidencia, String usuario) {
        byte[] jpeg = miniatura(evidencia);
        if (evidencia.getPublicaCambioEn() == null) {
            evidencia.fijarCambioEn(ahora()); // foto pública de antes de D66
        }
        int[] dimensiones = FotoPublica.dimensiones(jpeg)
                .orElseThrow(() -> new IllegalStateException("Miniatura ilegible"));
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("evidenciaId", evidencia.getId().toString());
        datos.put("hallazgoId", evidencia.getHallazgoId().toString());
        datos.put("cambioEn", DateTimeFormatter.ISO_INSTANT.format(evidencia.getPublicaCambioEn()));
        datos.put("sha256", sha256(jpeg));
        datos.put("ancho", dimensiones[0]);
        datos.put("alto", dimensiones[1]);
        datos.put("jpegBase64", Base64.getEncoder().encodeToString(jpeg));
        publicador.publishEvent(new CambioHallazgoConfirmado(CambioHallazgoConfirmado.TIPO_FOTO_PUBLICADA,
                usuario, datos));
    }

    /** Anuncia que la foto ya no es pública (la columna {@code publica} ya la fijó quien llama). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void anunciarRetirada(EvidenciaHallazgo evidencia, String usuario) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("evidenciaId", evidencia.getId().toString());
        datos.put("hallazgoId", evidencia.getHallazgoId().toString());
        datos.put("cambioEn", DateTimeFormatter.ISO_INSTANT.format(evidencia.getPublicaCambioEn()));
        publicador.publishEvent(new CambioHallazgoConfirmado(CambioHallazgoConfirmado.TIPO_FOTO_RETIRADA,
                usuario, datos));
    }

    /** La miniatura guardada; la genera y registra su ruta si no existe (o si el archivo se perdió). */
    private byte[] miniatura(EvidenciaHallazgo evidencia) {
        if (evidencia.getRutaMiniatura() != null) {
            try (InputStream datos = almacen.abrir(evidencia.getRutaMiniatura())) {
                return datos.readAllBytes();
            } catch (IOException | UncheckedIOException e) {
                log.warn("Falta la miniatura de la evidencia {}; se genera de nuevo", evidencia.getId());
            }
        }
        FotoPublica.Miniatura nueva;
        try (InputStream original = almacen.abrir(evidencia.getRuta())) {
            nueva = FotoPublica.generar(original.readAllBytes());
        } catch (FotoPublica.ImagenNoAdmitidaException e) {
            throw new DatosHallazgoInvalidosException("publica", e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar la miniatura", e);
        }
        evidencia.registrarMiniatura(
                almacen.guardarMiniatura(evidencia.getHallazgoId(), evidencia.getId(), nueva.jpeg()));
        return nueva.jpeg();
    }

    private static String sha256(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
