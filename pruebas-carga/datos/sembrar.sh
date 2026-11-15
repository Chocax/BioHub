#!/usr/bin/env bash
# Siembra fichas sintéticas en mysql-lectura (local, Docker). Idempotente: borra primero las de carga.
# Por defecto 2000 (ASR1/ASR4). Para otro volumen:  FICHAS=10000 ./datos/sembrar.sh   (HU-06 c.3 / CG-02)
# FICHAS debe ser un entero entre 1 y 99999. Todas con el prefijo c0a9a000, sin coordenadas y 20 % EN_PELIGRO.
set -euo pipefail
cd "$(dirname "$0")/../.."
FICHAS="${FICHAS:-2000}"
[[ "$FICHAS" =~ ^[0-9]{1,5}$ ]] && [ "$FICHAS" -ge 1 ] || { echo "FICHAS debe ser un entero entre 1 y 99999 (recibido: '$FICHAS')" >&2; exit 2; }
set -a; . ./.env; set +a
# MYSQL_PWD por entorno (no como argumento de docker exec, visible con `ps`; SEC-J-14)
run() { MYSQL_PWD="$MYSQL_LECTURA_ROOT_PASSWORD" docker exec -i -e MYSQL_PWD biohub-mysql-lectura mysql -uroot --default-character-set=utf8mb4 consulta_publica "$@"; }
LIMPIAR_RABBIT=0 pruebas-carga/datos/limpiar.sh >/dev/null   # sin tocar la cola de las réplicas ya levantadas
# @total llega por la misma sesión SQL (entero ya validado arriba); la semilla usa 2000 si no se define
{ echo "SET @total = ${FICHAS};"; cat pruebas-carga/datos/semilla-fichas.sql; } | run
