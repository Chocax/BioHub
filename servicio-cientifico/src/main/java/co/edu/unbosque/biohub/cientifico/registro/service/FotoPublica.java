package co.edu.unbosque.biohub.cientifico.registro.service;

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
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Copia para el portal público de una fotografía (D65): a lo sumo {@value #LADO_MAXIMO} px de lado, en JPEG y sin
 * canal alfa. Al volver a codificarla tampoco viaja ningún metadato del archivo original.
 */
final class FotoPublica {

    static final int LADO_MAXIMO = 1200;
    private static final float CALIDAD = 0.85f;

    private FotoPublica() {
    }

    static Optional<byte[]> reducir(byte[] original) throws IOException {
        BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(original));
        if (imagen == null) {
            return Optional.empty();
        }
        double escala = Math.min(1.0, (double) LADO_MAXIMO / Math.max(imagen.getWidth(), imagen.getHeight()));
        int ancho = Math.max(1, (int) Math.round(imagen.getWidth() * escala));
        int alto = Math.max(1, (int) Math.round(imagen.getHeight() * escala));

        BufferedImage destino = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE); // un PNG transparente queda sobre blanco
            g.fillRect(0, 0, ancho, alto);
            g.drawImage(imagen, 0, 0, ancho, alto, null);
        } finally {
            g.dispose();
        }

        Iterator<ImageWriter> escritores = ImageIO.getImageWritersByFormatName("jpeg");
        if (!escritores.hasNext()) {
            return Optional.empty();
        }
        ImageWriter escritor = escritores.next();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ImageOutputStream flujo = ImageIO.createImageOutputStream(salida)) {
            escritor.setOutput(flujo);
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametros.setCompressionQuality(CALIDAD);
            escritor.write(null, new IIOImage(destino, null, null), parametros);
        } finally {
            escritor.dispose();
        }
        return Optional.of(salida.toByteArray());
    }
}
