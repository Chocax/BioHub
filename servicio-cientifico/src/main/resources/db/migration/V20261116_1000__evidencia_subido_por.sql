-- HU-11 c.3: toda carga registra quien la hizo. La subida de evidencias (HU-01 c.2) no lo guardaba.
-- NULL solo por compatibilidad con filas anteriores a esta migracion (no hay ninguna en produccion).
-- Compatible con Aiven: sin triggers ni CREATE TABLE ... SELECT; la tabla ya tiene PK.
ALTER TABLE hallazgo_evidencia ADD COLUMN subido_por VARCHAR(100) NULL AFTER sha256;
