-- Fotos publicas por evento (D66, contrato 04 §7.6). El Servicio Cientifico publica la miniatura (JPEG <= 600 KB,
-- <= 1200 px, sin metadatos) en evidencia.publicada y consulta-publica la guarda aqui, de modo que el trafico publico
-- nunca toca al cientifico. La especie NO se guarda: sale del JOIN con ficha_publica por hallazgo_id, asi una
-- reclasificacion no exige otro evento. Sin coordenadas ni investigador (D14).
--
-- evidencia_id es el fotoId que ve el portal (UUID del evidencia, inmutable). Una retirada deja la fila con
-- publica = FALSE y sin bytes: asi un evidencia.publicada anterior que llegue tarde (cambio_en menor) se ignora.
-- cambio_en tiene microsegundos (DATETIME(6)) y se guarda en UTC; ordena los eventos de una misma foto.

CREATE TABLE foto_publica (
  evidencia_id BINARY(16)       NOT NULL,
  hallazgo_id  BINARY(16)       NOT NULL,
  bytes        MEDIUMBLOB       NULL,
  sha256       CHAR(64) CHARACTER SET ascii NULL,
  ancho        SMALLINT UNSIGNED NULL,
  alto         SMALLINT UNSIGNED NULL,
  publica      BOOLEAN          NOT NULL DEFAULT FALSE,
  cambio_en    DATETIME(6)      NOT NULL,

  CONSTRAINT pk_foto_publica PRIMARY KEY (evidencia_id),
  CONSTRAINT ck_foto_publica_completa CHECK (publica = FALSE OR (bytes IS NOT NULL AND sha256 IS NOT NULL
      AND ancho IS NOT NULL AND alto IS NOT NULL)),
  CONSTRAINT ck_foto_publica_dimensiones CHECK ((ancho IS NULL OR ancho BETWEEN 1 AND 1200)
      AND (alto IS NULL OR alto BETWEEN 1 AND 1200))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Fotos de una ficha (JOIN del catalogo y de la ruta por nombre)
CREATE INDEX idx_foto_publica_hallazgo ON foto_publica (hallazgo_id);
