package co.edu.unbosque.biohub.cientifico.registro.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Miniatura para el portal público de una fotografía (D66): JPEG de a lo sumo {@value #LADO_MAXIMO} px de lado y
 * {@value #TAMANO_MAXIMO} bytes, sin canal alfa y sin metadatos (al volver a codificarla no viaja EXIF, XMP ni
 * comentarios del original). Se genera <b>una sola vez</b>, al marcar la foto como pública.
 *
 * <p><b>Límite de píxeles:</b> las dimensiones se leen del encabezado con un {@link ImageReader} sin decodificar, y
 * una imagen de más de {@value #PIXELES_MAXIMOS} píxeles se rechaza (SEC-R-4, BE-R-14: un PNG de 9 MB puede declarar
 * decenas de miles de píxeles por lado y agotar el heap). Dentro del límite, el decodificado usa submuestreo de
 * origen para no cargar la imagen entera.
 *
 * <p><b>Tope de 600 KB:</b> es el límite que consulta-publica verifica antes de guardar (04 §7.6). Se usa
 * 600&nbsp;000 bytes (no 600&nbsp;KiB) para que quepa con cualquier interpretación. Si la primera codificación lo
 * supera, se baja la calidad por pasos y, si aún no cabe, el lado en un 20&nbsp;% hasta que quepa.
 */
final class FotoPublica {

    static final int LADO_MAXIMO = 1200;
    static final long PIXELES_MAXIMOS = 40_000_000L;
    static final int TAMANO_MAXIMO = 600_000;

    private static final float[] CALIDADES = {0.85f, 0.75f, 0.65f, 0.55f, 0.45f, 0.35f, 0.25f};
    private static final int LADO_MINIMO = 64;

    private FotoPublica() {
    }

    /** La miniatura ya codificada y sus dimensiones. */
    record Miniatura(byte[] jpeg, int ancho, int alto) {
    }

    /** La imagen no se puede usar: formato ilegible o más de {@link #PIXELES_MAXIMOS} píxeles. */
    static final class ImagenNoAdmitidaException extends RuntimeException {
        ImagenNoAdmitidaException(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * @throws ImagenNoAdmitidaException si la imagen es ilegible o pasa de 40 MP
     * @throws IOException               si falla la lectura o la codificación
     */
    static Miniatura generar(byte[] original) throws IOException {
        try (ImageInputStream entrada = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(entrada);
            if (!lectores.hasNext()) {
                throw new ImagenNoAdmitidaException("La imagen no se pudo leer.");
            }
            ImageReader lector = lectores.next();
            BufferedImage imagen;
            try {
                lector.setInput(entrada, true, true);
                // Solo el encabezado: nada se decodifica antes de comprobar el tamaño.
                int anchoOriginal = lector.getWidth(0);
                int altoOriginal = lector.getHeight(0);
                if (anchoOriginal < 1 || altoOriginal < 1) {
                    throw new ImagenNoAdmitidaException("La imagen no se pudo leer.");
                }
                if ((long) anchoOriginal * altoOriginal > PIXELES_MAXIMOS) {
                    throw new ImagenNoAdmitidaException("La imagen supera los 40 megapíxeles permitidos.");
                }
                ImageReadParam parametros = lector.getDefaultReadParam();
                int salto = Math.max(1, Math.max(anchoOriginal, altoOriginal) / (LADO_MAXIMO * 2));
                if (salto > 1) {
                    parametros.setSourceSubsampling(salto, salto, 0, 0);
                }
                imagen = lector.read(0, parametros);
            } catch (IllegalArgumentException | IndexOutOfBoundsException | IOException e) {
                // Formato dañado o no soportado (p. ej. JPEG CMYK): la causa no se devuelve al cliente.
                throw new ImagenNoAdmitidaException("La imagen no se pudo leer.");
            } finally {
                lector.dispose();
            }
            return codificar(imagen);
        }
    }

    /** Lee ancho y alto de un JPEG ya generado (sin decodificarlo). */
    static Optional<int[]> dimensiones(byte[] jpeg) {
        try (ImageInputStream entrada = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(entrada);
            if (!lectores.hasNext()) {
                return Optional.empty();
            }
            ImageReader lector = lectores.next();
            try {
                lector.setInput(entrada, true, true);
                return Optional.of(new int[] {lector.getWidth(0), lector.getHeight(0)});
            } finally {
                lector.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Miniatura codificar(BufferedImage imagen) throws IOException {
        double escala = Math.min(1.0, (double) LADO_MAXIMO / Math.max(imagen.getWidth(), imagen.getHeight()));
        int ancho = Math.max(1, (int) Math.round(imagen.getWidth() * escala));
        int alto = Math.max(1, (int) Math.round(imagen.getHeight() * escala));
        while (true) {
            BufferedImage reducida = redimensionar(imagen, ancho, alto);
            for (float calidad : CALIDADES) {
                byte[] jpeg = aJpeg(reducida, calidad);
                if (jpeg.length <= TAMANO_MAXIMO) {
                    return new Miniatura(jpeg, ancho, alto);
                }
            }
            if (Math.max(ancho, alto) <= LADO_MINIMO) {
                throw new ImagenNoAdmitidaException("La imagen no se pudo reducir al tamaño permitido.");
            }
            ancho = Math.max(1, (int) Math.round(ancho * 0.8));
            alto = Math.max(1, (int) Math.round(alto * 0.8));
        }
    }

    private static BufferedImage redimensionar(BufferedImage imagen, int ancho, int alto) {
        BufferedImage destino = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE); // un PNG transparente queda sobre blanco
            g.fillRect(0, 0, ancho, alto);
            g.drawImage(imagen, 0, 0, ancho, alto, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    private static byte[] aJpeg(BufferedImage imagen, float calidad) throws IOException {
        Iterator<ImageWriter> escritores = ImageIO.getImageWritersByFormatName("jpeg");
        if (!escritores.hasNext()) {
            throw new IOException("No hay codificador JPEG disponible");
        }
        ImageWriter escritor = escritores.next();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ImageOutputStream flujo = ImageIO.createImageOutputStream(salida)) {
            escritor.setOutput(flujo);
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametros.setCompressionQuality(calidad);
            // Sin metadatos: IIOImage(imagen, null, null) no escribe EXIF, XMP ni comentarios.
            escritor.write(null, new IIOImage(imagen, null, null), parametros);
        } finally {
            escritor.dispose();
        }
        return salida.toByteArray();
    }
}
