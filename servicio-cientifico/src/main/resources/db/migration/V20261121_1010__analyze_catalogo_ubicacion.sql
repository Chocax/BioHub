-- DB-J22-1 (07-reporte-qa, DB-07): tras la carga del catálogo, InnoDB deja estadísticas de ~1 fila en
-- ubicacion_departamento y ubicacion_region, y MySQL elige un plan degradado ("Range checked for each record") en
-- las búsquedas por investigador+fecha y especie+fechas. ANALYZE TABLE las recalcula.
-- Necesita SELECT e INSERT sobre las tablas, que ya tiene el usuario de migración (cientifico_migr, deploy/aiven/01).
-- Es idempotente y no cambia datos ni esquema. Si el catálogo crece, repetirla como mantenimiento.
ANALYZE TABLE ubicacion_region, ubicacion_departamento;
