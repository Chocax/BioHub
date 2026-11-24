#!/usr/bin/env bash
# HU-06 c.3 / CG-02 (RNF5): UNA corrida = fase A (100 búsquedas distintas, 1 VU) + fase B (100 distintas, 10 VUs), con la
# caché de Redis (biohub:publica:*) vaciada justo antes de CADA fase. Antes: compose de carga con 1 réplica y
# `FICHAS=10000 ./datos/sembrar.sh` (ver README, sección "HU-06 c.3 / CG-02").
# Uso (desde pruebas-carga/):  RUN=1 ./correr-hu06-frio.sh
# Variables: RUN (def. 1; también es la SEED del generador de búsquedas), FICHAS (def. 10000; se comprueba contra la BD),
#   WARMUP (def. 1: 24 búsquedas de calentamiento con size 7/11/13, que no coinciden con ninguna medida; la caché se vacía
#   después), PREFIJO (def. "corrida": los JSON son resultados/hu06-<PREFIJO><RUN>-fase-{a,b}.json), NOTA (texto libre
#   que queda en el JSON), BASE_URL_K6 (def. http://nginx:8080, k6 dentro de la red biohub_default).
# Termina con código != 0 si alguna fase incumple un umbral; los JSON se escriben igual. Un JSON de una corrida inválida por
# el banco de pruebas se renombra a descartado-... a mano (D42).
set -uo pipefail
cd "$(dirname "$0")"
RUN="${RUN:-1}"; FICHAS="${FICHAS:-10000}"; WARMUP="${WARMUP:-1}"; PREFIJO="${PREFIJO:-corrida}"
BASE_URL_K6="${BASE_URL_K6:-http://nginx:8080}"
valor_env() { grep -E "^$1=" ../.env | head -1 | cut -d= -f2-; }

# D42: réplicas y CPU/memoria REALES del compose en marcha (no lo que diga una variable)
mapfile -t cont < <(docker ps --filter label=com.docker.compose.project=biohub-carga --filter label=com.docker.compose.service=consulta-publica --format '{{.Names}}')
[ "${#cont[@]}" -ge 1 ] || { echo "No hay réplicas de consulta-publica del compose biohub-carga" >&2; exit 2; }
export REPLICAS="${#cont[@]}"
nano=$(docker inspect "${cont[0]}" --format '{{.HostConfig.NanoCpus}}')
export CPUS=$(awk -v n="$nano" 'BEGIN{printf "%g", n/1000000000}')
MEM=$(docker inspect "${cont[0]}" --format '{{.HostConfig.Memory}}')
MEM="$(( MEM / 1048576 ))MiB"

# Las fichas sembradas deben ser las declaradas
n=$(MYSQL_PWD="$(valor_env MYSQL_LECTURA_ROOT_PASSWORD)" docker exec -i -e MYSQL_PWD biohub-mysql-lectura mysql -uroot -N -B consulta_publica \
    -e "SELECT COUNT(*) FROM ficha_publica WHERE HEX(hallazgo_id) LIKE 'C0A9A000%'" 2>/dev/null)
[ "$n" = "$FICHAS" ] || { echo "Fichas de carga en la BD = '$n', se esperaban $FICHAS (¿falta FICHAS=$FICHAS ./datos/sembrar.sh?)" >&2; exit 2; }
echo ">> RUN=$RUN réplicas=$REPLICAS cpus=$CPUS mem=$MEM fichas=$n"

if [ "$WARMUP" = 1 ]; then
  for i in 1 2 3 4 5 6 7 8; do
    for sz in 7 11 13; do
      curl -s -o /dev/null "http://127.0.0.1:18080/api/publica/fichas?q=atelopus&page=$((i % 3))&size=$sz"
    done
  done
  echo ">> calentamiento hecho (24 peticiones, size 7/11/13)"
fi

rc=0
for f in a b; do
  F=$(echo "$f" | tr a-z A-Z)
  ./datos/vaciar-cache.sh || { echo "No se pudo vaciar la caché" >&2; exit 2; }
  RED_K6=biohub_default ./correr-k6.sh k6/busqueda-publica-fria.js -e FASE="$F" -e SEED="$RUN" -e RUN="$RUN" -e FICHAS="$FICHAS" \
    -e BASE_URL="$BASE_URL_K6" -e MEM="$MEM" -e NOTA="${NOTA:-}" -e SALIDA="/resultados/hu06-${PREFIJO}${RUN}-fase-${f}.json" || rc=1
done
exit $rc
