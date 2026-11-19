-- D65: el investigador elige qué fotografías se pueden mostrar en el portal público. Por defecto ninguna:
-- una foto interna (por ejemplo, una que muestre el lugar) nunca sale sin que alguien lo decida.
-- Compatible con Aiven: sin triggers ni CREATE TABLE ... SELECT; la tabla ya tiene PK y cientifico_app ya tiene
-- UPDATE sobre ella (deploy/aiven/03).
ALTER TABLE hallazgo_evidencia ADD COLUMN publica BOOLEAN NOT NULL DEFAULT FALSE AFTER subido_por;

CREATE INDEX ix_hallazgo_evidencia_publica ON hallazgo_evidencia (publica, tipo, creado_en);
