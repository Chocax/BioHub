#!/usr/bin/env bash
# Siembra fotos públicas sintéticas (D66) en foto_publica de mysql-lectura: una por cada una de las primeras FOTOS especies
# del catálogo (orden de /api/publica/especies, así la página 0 de la portada trae FOTOS fotoId). Todas con el mismo JPEG
# (datos/foto-semilla.jpg: 1200x900, ~330 KB, sin metadatos; el tope real de una miniatura es 600 KB). Sirve a la portada de
# ASR1 (consulta-publica-portada.js). Requiere las fichas ya sembradas (./datos/sembrar.sh). Idempotente.
# evidencia_id = c0a9a000-0000-4000-9000-<12 hex> (prefijo de carga: limpiar.sh las borra). Nunca toca fotos reales.
# Uso:  FOTOS=12 ./datos/sembrar-fotos.sh      (1 a 200)
set -euo pipefail
cd "$(dirname "$0")/../.."
FOTOS="${FOTOS:-12}"
[[ "$FOTOS" =~ ^[0-9]{1,3}$ ]] && [ "$FOTOS" -ge 1 ] && [ "$FOTOS" -le 200 ] || { echo "FOTOS debe ser un entero entre 1 y 200 (recibido: '$FOTOS')" >&2; exit 2; }
JPEG="${FOTO_JPEG:-pruebas-carga/datos/foto-semilla.jpg}"
ANCHO="${FOTO_ANCHO:-1200}"; ALTO="${FOTO_ALTO:-900}"
[ -f "$JPEG" ] || { echo "No existe $JPEG" >&2; exit 2; }
[ "$(stat -c %s "$JPEG")" -le 614400 ] || { echo "El JPEG pasa de 600 KB (tope de una miniatura, D66)" >&2; exit 2; }
set -a; . ./.env; set +a
run() { MYSQL_PWD="$MYSQL_LECTURA_ROOT_PASSWORD" docker exec -i -e MYSQL_PWD biohub-mysql-lectura mysql -uroot --default-character-set=utf8mb4 consulta_publica "$@"; }
SHA=$(sha256sum "$JPEG" | cut -d' ' -f1)
{
  echo "DELETE FROM foto_publica WHERE HEX(evidencia_id) LIKE 'C0A9A000%';"
  printf "SET @b = UNHEX('"; xxd -p "$JPEG" | tr -d '\n'; echo "');"
  cat <<SQL
INSERT INTO foto_publica (evidencia_id, hallazgo_id, bytes, sha256, ancho, alto, publica, cambio_en)
SELECT UNHEX(REPLACE(CONCAT('c0a9a000-0000-4000-9000-', LPAD(HEX(x.rn), 12, '0')), '-', '')), x.hid, @b, '${SHA}',
       ${ANCHO}, ${ALTO}, TRUE, UTC_TIMESTAMP(6)
FROM (SELECT MIN(hallazgo_id) AS hid, nombre_cientifico, ROW_NUMBER() OVER (ORDER BY nombre_cientifico) AS rn
      FROM ficha_publica GROUP BY nombre_cientifico ORDER BY nombre_cientifico LIMIT ${FOTOS}) x;
SELECT COUNT(*) AS fotos_de_carga, SUM(LENGTH(bytes)) AS bytes_totales FROM foto_publica WHERE HEX(evidencia_id) LIKE 'C0A9A000%';
SQL
} | run
