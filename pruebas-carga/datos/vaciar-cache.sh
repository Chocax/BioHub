#!/usr/bin/env bash
# Vacía SOLO la caché de consulta-publica en Redis (claves biohub:publica:*), con SCAN + DEL; nunca FLUSHALL/FLUSHDB
# (el Redis del compose raíz es compartido con el desarrollo). Escribe en stdout cuántas claves había y cuántas quedan.
# Lo usa correr-hu06-frio.sh justo antes de cada fase (caché fría, HU-06 c.3 / CG-02).
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
# REDISCLI_AUTH por entorno (no por argumento; SEC-J-14)
rc() { REDISCLI_AUTH="$REDIS_PASSWORD" docker exec -e REDISCLI_AUTH biohub-redis redis-cli "$@" 2>/dev/null; }
antes=$(rc --scan --pattern 'biohub:publica:*' | wc -l)
REDISCLI_AUTH="$REDIS_PASSWORD" docker exec -e REDISCLI_AUTH biohub-redis sh -c "redis-cli --scan --pattern 'biohub:publica:*' | xargs -r -n 500 redis-cli del" >/dev/null 2>&1
despues=$(rc --scan --pattern 'biohub:publica:*' | wc -l)
echo "claves biohub:publica:* antes=${antes} despues=${despues}"
[ "$despues" -eq 0 ] || { echo "ERROR: la caché no quedó vacía" >&2; exit 1; }
