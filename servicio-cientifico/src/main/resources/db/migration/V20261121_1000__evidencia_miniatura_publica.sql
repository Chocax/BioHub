-- D66: la miniatura de una foto pública se genera una sola vez, al marcarla, y se guarda junto al original.
-- ruta_miniatura: ruta relativa en el volumen de evidencias (NULL = aún sin miniatura; las fotos que ya eran
-- públicas antes de D66 se migran al arrancar el servicio).
-- publica_cambio_en: instante del último cambio de visibilidad; viaja como `cambioEn` en evidencia.publicada /
-- evidencia.retirada y ordena los eventos en consulta-publica.
-- Compatible con Aiven: solo ALTER TABLE ADD COLUMN (sin triggers, sin CREATE TABLE ... SELECT).
ALTER TABLE hallazgo_evidencia
  ADD COLUMN ruta_miniatura VARCHAR(500) NULL AFTER publica,
  ADD COLUMN publica_cambio_en DATETIME(6) NULL AFTER ruta_miniatura;
