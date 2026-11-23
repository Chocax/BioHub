#!/usr/bin/env bash
# =============================================================================================================
# Levanta BioHub completo en Linux (o macOS): Docker (MySQL, Redis, RabbitMQ, Keycloak), los 5 servicios Spring
# Boot y las dos webs. Hace lo mismo que levantar.ps1 (Windows) y va diciendo qué arranca, en qué puerto y si
# quedó bien. Los logs quedan en .biohub-local/logs (no se suben al repo).
#
# Uso:
#   ./levantar.sh                 todo, con las bases en Docker (perfil local)
#   ./levantar.sh --sembrar       lo mismo y además crea datos de ejemplo (solo perfil local)
#   ./levantar.sh --estado        qué está arriba, sin arrancar nada
#   ./levantar.sh --detener       apaga servicios y webs y detiene los contenedores (los datos se conservan)
# Otras opciones:
#   --perfil aiven                bases en Aiven (necesita lo de docs/equipo/09-despliegue.md §4)
#   --compilar                    compila aunque no haya cambios
#   --reimportar-realm            vuelve a importar el realm (si los usuarios de prueba no entran)
#   --env <archivo>               otro archivo de variables (por defecto .env de la raíz)
# =============================================================================================================
set -uo pipefail

PERFIL=local; SEMBRAR=0; COMPILAR=0; REIMPORTAR=0; ESTADO=0; DETENER=0; ARCHIVO_ENV=.env
while [ $# -gt 0 ]; do
  case "$1" in
    --perfil) PERFIL="${2:-}"; shift ;;
    --sembrar) SEMBRAR=1 ;;
    --compilar) COMPILAR=1 ;;
    --reimportar-realm) REIMPORTAR=1 ;;
    --estado) ESTADO=1 ;;
    --detener) DETENER=1 ;;
    --env) ARCHIVO_ENV="${2:-}"; shift ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Opción desconocida: $1 (usa --help)"; exit 2 ;;
  esac
  shift
done
[ "$PERFIL" = local ] || [ "$PERFIL" = aiven ] || { echo "--perfil debe ser local o aiven"; exit 2; }

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CARPETA="$RAIZ/.biohub-local"
LOGS="$CARPETA/logs"
ESTADO_ARCHIVO="$CARPETA/estado-linux.txt"
INICIO=$(date +%s)
mkdir -p "$LOGS"
cd "$RAIZ" || exit 1
case "$ARCHIVO_ENV" in /*|[A-Za-z]:*) RUTA_ENV="$ARCHIVO_ENV" ;; *) RUTA_ENV="$RAIZ/$ARCHIVO_ENV" ;; esac

# ------------------------------------------------------------------------------------------------ utilidades
if [ -t 1 ]; then V=$'\e[32m'; R=$'\e[31m'; A=$'\e[33m'; C=$'\e[36m'; G=$'\e[90m'; N=$'\e[0m'; else V=; R=; A=; C=; G=; N=; fi
titulo() { echo; echo "${C}[$(date +%H:%M:%S)] $1${N}"; }
bien() { echo "   ${V}OK${N}    $1"; }
mal() { echo "   ${R}FALLA${N} $1"; }
aviso() { echo "   ${A}OJO${N}   $1"; }
nota() { echo "${G}         $1${N}"; }
salir() { echo; mal "$1"; echo; exit 1; }

declare -A VARS=()
leer_env() {
  VARS=()
  [ -f "$RUTA_ENV" ] || return 0
  local linea clave valor
  while IFS= read -r linea || [ -n "$linea" ]; do
    linea="${linea%$'\r'}"
    linea="$(printf '%s' "$linea" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    case "$linea" in ''|'#'*) continue ;; *=*) ;; *) continue ;; esac
    clave="$(printf '%s' "${linea%%=*}" | sed 's/[[:space:]]*$//')"
    valor="$(printf '%s' "${linea#*=}" | sed 's/^[[:space:]]*//')"
    if [ ${#valor} -ge 2 ]; then
      case "$valor" in \"*\") valor="${valor:1:${#valor}-2}" ;; \'*\') valor="${valor:1:${#valor}-2}" ;; esac
    fi
    VARS["$clave"]="$valor"
  done < "$RUTA_ENV"
}
valor() { local v="${VARS[$1]:-}"; if [ -n "$v" ]; then printf '%s' "$v"; else printf '%s' "${2:-}"; fi; }

puerto_abierto() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
responde() { # url [texto que debe contener]
  local cuerpo; cuerpo="$(curl -fsS --max-time 4 "$1" 2>/dev/null)" || return 1
  [ -z "${2:-}" ] || printf '%s' "$cuerpo" | grep -q -- "$2"
}
ultimas_lineas() { [ -f "$1" ] && tail -n "${2:-25}" "$1" | while IFS= read -r l; do nota "$l"; done; }

# esperar "texto" limite [pid] comando...   -> imprime los segundos; código 1 si se acabó el tiempo, 2 si murió el proceso
esperar() {
  local texto="$1" limite="$2" pid="$3"; shift 3
  local t0; t0=$(date +%s)
  while true; do
    if "$@" >/dev/null 2>&1; then printf '\r%70s\r' '' >&2; echo $(( $(date +%s) - t0 )); return 0; fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then printf '\r%70s\r' '' >&2; return 2; fi
    local seg=$(( $(date +%s) - t0 ))
    if [ "$seg" -ge "$limite" ]; then printf '\r%70s\r' '' >&2; return 1; fi
    printf '\r%s         esperando %s... %s s%s' "$G" "$texto" "$seg" "$N" >&2
    sleep 1.5
  done
}

# Rellena a N caracteres contando letras, no bytes (printf %-Ns descuadra con las tildes).
ancho() { local t="$1"; printf '%s%*s' "$t" $(( $2 > ${#t} ? $2 - ${#t} : 0 )) ''; }
clave_al_azar() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

# ----------------------------------------------------------------------------------- qué hay que levantar
SERVICIOS=( # nombre|variable del puerto|puerto por defecto|texto
  "servicio-cientifico|SERVICIO_CIENTIFICO_PORT|8081|Servicio científico"
  "servicio-equipos|SERVICIO_EQUIPOS_PORT|8082|Servicio de equipos"
  "servicio-consulta-publica|SERVICIO_CONSULTA_PUBLICA_PORT|8083|Servicio de consulta pública"
  "servicio-reportes|SERVICIO_REPORTES_PORT|8084|Servicio de reportes"
  "api-gateway|API_GATEWAY_PORT|8080|API Gateway"
)
WEBS=("web-interna|5173|Web interna" "web-publica|5174|Web pública")
CONTENEDORES=( # servicio|variable del puerto|puerto por defecto|texto|solo en el perfil
  "mysql-transaccional|MYSQL_TRANSACCIONAL_PORT|3307|MySQL transaccional (científico y equipos)|local"
  "mysql-lectura|MYSQL_LECTURA_PORT|3308|MySQL de lectura (portal público)|local"
  "mysql-analitica|MYSQL_ANALITICA_PORT|3309|MySQL analítica (reportes)|local"
  "mysql-gateway|MYSQL_GATEWAY_PORT|3310|MySQL del gateway (API keys)|local"
  "redis|REDIS_PORT|6379|Redis (caché y límites)|"
  "rabbitmq|RABBITMQ_PORT|5672|RabbitMQ (eventos)|"
  "keycloak|KEYCLOAK_PORT|8180|Keycloak (inicio de sesión)|"
)
USUARIOS=(
  "investigador1|Investigador de campo|registra hallazgos con fotos y audios, edita fichas, reclasifica"
  "cientifico1|Científico de alto rango|todo lo anterior + mapa y coordenadas exactas"
  "auditor1|Auditor científico|consulta, historial y auditoría (solo lectura)"
  "analista1|Analista ambiental|búsqueda y reportes de presión"
  "admin1|Administrador|equipos, usuarios y roles, API keys, reportes"
  "sinrol1|sin rol|para comprobar que sin rol no se entra"
)
declare -A MIGRACION=([CIENTIFICO]=cientifico_migr [EQUIPOS]=equipos_migr [CONSULTA]=consulta_migr [REPORTES]=reportes_migr [GATEWAY]=gateway_migr)

# ---------------------------------------------------------------------------------------------- Docker
nombre_contenedor() { echo "$PROYECTO-$1"; }
OVERRIDE=""
preparar_override() { # docker-compose.yml fija los nombres biohub-*; con otro proyecto se renombran
  OVERRIDE=""
  [ "$PROYECTO" = biohub ] && return 0
  OVERRIDE="$CARPETA/compose-$PROYECTO.yml"
  { echo 'services:'; for c in mysql-transaccional mysql-lectura mysql-analitica mysql-gateway redis rabbitmq rabbitmq-usuarios keycloak; do
      printf '  %s:\n    container_name: %s-%s\n' "$c" "$PROYECTO" "$c"; done; } > "$OVERRIDE"
}
compose() { # log argumentos...
  local log="$1"; shift
  local archivos=(-f "$RAIZ/docker-compose.yml"); [ -n "$OVERRIDE" ] && archivos+=(-f "$OVERRIDE")
  docker compose -p "$PROYECTO" --env-file "$RUTA_ENV" "${archivos[@]}" "$@" > "$log" 2>&1
}
estado_contenedor() { docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.State.ExitCode}}' "$1" 2>/dev/null || echo 'no existe||'; }
contenedor_sano() { local e; e="$(estado_contenedor "$1")"; [ "${e%%|*}" = running ] && { local s="${e#*|}"; s="${s%%|*}"; [ -z "$s" ] || [ "$s" = healthy ]; }; }
contenedor_termino() { local e; e="$(estado_contenedor "$1")"; [ "${e%%|*}" = exited ]; }
docker_arriba() {
  command -v docker >/dev/null || salir 'No encuentro Docker. Instálalo (https://docs.docker.com/engine/install/) con el plugin compose.'
  docker info >/dev/null 2>&1 || salir 'Docker no responde. Arráncalo (sudo systemctl start docker) o revisa que tu usuario esté en el grupo docker.'
}

# Corre infra/mysql/initdb/01-usuarios.sh dentro del contenedor (es repetible): usuarios *_app y *_migr y, en la
# transaccional, el bloqueo del historial si las tablas ya existen. Deja la salida en un log y la imprime filtrada.
PERMISOS_SALIDA=""
permisos_mysql() {
  local log="$LOGS/mysql-usuarios-$1.log"
  docker exec "$(nombre_contenedor "$1")" bash /docker-entrypoint-initdb.d/01-usuarios.sh > "$log" 2>&1
  local codigo=$?
  PERMISOS_SALIDA="$(grep -E '\[biohub\]|ERROR' "$log" | sed 's/^\[biohub\][[:space:]]*//')"
  return $codigo
}

# --------------------------------------------------------------------------------------- procesos propios
iniciar() { # nombre carpeta comando...
  local nombre="$1" dir="$2"; shift 2
  local log="$LOGS/$nombre.log"
  [ -f "$log" ] && mv -f "$log" "$log.anterior"
  # setsid (Linux) deja al proceso en su propio grupo: --detener apaga también los hijos (Vite, por ejemplo).
  local grupo=(); command -v setsid >/dev/null && grupo=(setsid)
  ( cd "$dir" && nohup "${grupo[@]}" "$@" > "$log" 2>&1 < /dev/null & echo $! > "$CARPETA/.pid-$nombre" )
  local pid; pid="$(cat "$CARPETA/.pid-$nombre")"; rm -f "$CARPETA/.pid-$nombre"
  echo "$nombre $pid $log" >> "$ESTADO_ARCHIVO"
}
pid_de() { awk -v n="$1" '$1 == n { p = $2 } END { print p }' "$ESTADO_ARCHIVO" 2>/dev/null; }
apagar_procesos() {
  local apagados=0 nombre pid resto
  if [ -f "$ESTADO_ARCHIVO" ]; then
    while read -r nombre pid resto; do
      if kill -0 "$pid" 2>/dev/null; then kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null; apagados=$((apagados + 1)); fi
    done < "$ESTADO_ARCHIVO"
    rm -f "$ESTADO_ARCHIVO"
  fi
  # Por si quedó algo de una corrida anterior escuchando en los puertos de BioHub (solo java y node).
  local puertos=() s w
  for s in "${SERVICIOS[@]}"; do IFS='|' read -r _ clave defecto _ <<< "$s"; puertos+=("$(valor "$clave" "$defecto")"); done
  for w in "${WEBS[@]}"; do IFS='|' read -r _ p _ <<< "$w"; puertos+=("$p"); done
  if command -v ss >/dev/null; then
    for p in "${puertos[@]}"; do
      for pid in $(ss -ltnpH "sport = :$p" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u); do
        case "$(ps -o comm= -p "$pid" 2>/dev/null)" in java|node) kill -TERM "$pid" 2>/dev/null && apagados=$((apagados + 1)) ;; esac
      done
    done
  fi
  [ "$apagados" -gt 0 ] && sleep 2
  echo "$apagados"
}

# ------------------------------------------------------------------------------------------- tabla final
mostrar_tabla() {
  titulo 'Así quedó BioHub'
  local caidos=0 f nombre p texto clave defecto solo ok extra
  echo; printf '   %-44s %-7s %-8s %s\n' 'Componente' 'Puerto' 'Estado' 'Dirección'; echo "   ${G}$(printf '%.0s-' {1..100})${N}"
  fila() { printf '   %s %-7s ' "$(ancho "$1" 44)" "$2"; if [ "$3" = 1 ]; then printf '%s%-8s%s ' "$V" arriba "$N"; else printf '%s%-8s%s ' "$R" caído "$N"; caidos=$((caidos + 1)); fi; echo "$4"; }
  for f in "${WEBS[@]}"; do IFS='|' read -r nombre p texto <<< "$f"; ok=0; responde "http://localhost:$p/" && ok=1; fila "$texto" "$p" "$ok" "http://localhost:$p"; done
  for f in "${SERVICIOS[@]}"; do IFS='|' read -r nombre clave defecto texto <<< "$f"; p="$(valor "$clave" "$defecto")"; ok=0
    responde "http://127.0.0.1:$p/actuator/health" '"UP"' && ok=1; fila "$texto" "$p" "$ok" "http://localhost:$p"; done
  for f in "${CONTENEDORES[@]}"; do IFS='|' read -r nombre clave defecto texto solo <<< "$f"
    [ -n "$solo" ] && [ "$solo" != "$PERFIL" ] && continue
    ok=0; contenedor_sano "$(nombre_contenedor "$nombre")" && ok=1; extra="contenedor $(nombre_contenedor "$nombre")"
    [ "$nombre" = keycloak ] && extra="http://localhost:$(valor "$clave" "$defecto")/admin"
    [ "$nombre" = rabbitmq ] && extra="panel http://localhost:$(valor RABBITMQ_MANAGEMENT_PORT 15672)"
    fila "$texto" "$(valor "$clave" "$defecto")" "$ok" "$extra"; done
  CAIDOS=$caidos
}
mostrar_usuarios() {
  titulo 'Usuarios de prueba (realm biohub) para la web interna'
  local c; c="$(valor KEYCLOAK_TEST_USERS_PASSWORD '')"
  echo "   Contraseña de todos: ${A}${c:-(la de KEYCLOAK_TEST_USERS_PASSWORD en tu .env)}${N}"; echo
  local u usuario rol hace
  for u in "${USUARIOS[@]}"; do IFS='|' read -r usuario rol hace <<< "$u"; printf '   %-15s %-26s %s\n' "$usuario" "$rol" "$hace"; done
  nota ''
  nota "Consola de Keycloak: http://localhost:$(valor KEYCLOAK_PORT 8180)/admin con KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD del .env."
  nota 'Más usuarios: entra como admin1 a "Usuarios" en la web interna y créalos ahí.'
}

# =========================================================================================== programa
leer_env
PROYECTO="$(valor COMPOSE_PROJECT_NAME biohub)"
preparar_override
echo; echo "${V}   BioHub · entorno local${N}"; nota "raíz: $RAIZ · variables: $RUTA_ENV · proyecto Docker: $PROYECTO"

if [ "$ESTADO" = 1 ]; then docker_arriba; mostrar_tabla; mostrar_usuarios; echo; exit 0; fi
if [ "$DETENER" = 1 ]; then
  titulo 'Apagando servicios y webs'
  bien "procesos detenidos: $(apagar_procesos)"
  if docker info >/dev/null 2>&1; then
    titulo 'Deteniendo contenedores (los datos se conservan)'
    if compose "$LOGS/docker-stop.log" stop; then bien 'contenedores detenidos'; else mal 'docker compose stop falló'; ultimas_lineas "$LOGS/docker-stop.log"; fi
  fi
  echo; nota 'Para borrar también los datos de Docker: docker compose down -v'; echo; exit 0
fi

# ------------------------------------------------------------------------------------- 1. herramientas
titulo '1/7 Revisando herramientas'
command -v java >/dev/null || salir 'No encuentro Java. Instala el JDK 21 (Temurin): https://adoptium.net'
version_java="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
[ "${version_java:-0}" -ge 21 ] 2>/dev/null || salir "Java $version_java: se necesita la 21 o superior."
bien "Java $version_java"
command -v mvn >/dev/null || salir 'No encuentro Maven (mvn). Instala Maven 3.9 o superior.'; bien 'Maven'
command -v node >/dev/null || salir 'No encuentro Node.js. Instala Node 22 o superior.'; bien "Node $(node -v)"
command -v curl >/dev/null || salir 'No encuentro curl (sudo apt install curl).'
docker_arriba; bien "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
docker compose version >/dev/null 2>&1 || salir 'Falta el plugin docker compose (v2).'

# ------------------------------------------------------------------------------------- 2. variables
titulo "2/7 Revisando variables ($(basename "$RUTA_ENV"), perfil $PERFIL)"
[ -f "$RUTA_ENV" ] || salir "No existe $RUTA_ENV. Cópialo del ejemplo:  cp .env.example .env  y cambia las contraseñas."
obligatorias=(REDIS_PASSWORD RABBITMQ_USER RABBITMQ_PASSWORD KEYCLOAK_ADMIN_USER KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_TEST_USERS_PASSWORD)
[ "$PERFIL" = local ] && obligatorias+=(MYSQL_TRANSACCIONAL_ROOT_PASSWORD MYSQL_CIENTIFICO_USER MYSQL_CIENTIFICO_PASSWORD MYSQL_EQUIPOS_USER
  MYSQL_EQUIPOS_PASSWORD MYSQL_LECTURA_ROOT_PASSWORD MYSQL_LECTURA_USER MYSQL_LECTURA_PASSWORD MYSQL_ANALITICA_ROOT_PASSWORD
  MYSQL_ANALITICA_USER MYSQL_ANALITICA_PASSWORD MYSQL_GATEWAY_ROOT_PASSWORD MYSQL_GATEWAY_USER MYSQL_GATEWAY_PASSWORD)
faltan=(); for v in "${obligatorias[@]}"; do [ -n "$(valor "$v" '')" ] || faltan+=("$v"); done
if [ ${#faltan[@]} -gt 0 ]; then mal 'Faltan estas variables (cópialas de .env.example y ponles valor):'; for v in "${faltan[@]}"; do nota "- $v"; done; salir 'Completa el .env y vuelve a correr el script.'; fi
bien "${#obligatorias[@]} variables obligatorias presentes"
sin_cambiar=(); for k in "${!VARS[@]}"; do case "${VARS[$k]}" in cambiar_*) sin_cambiar+=("$k") ;; esac; done
[ ${#sin_cambiar[@]} -gt 0 ] && aviso "siguen con el valor de ejemplo: ${sin_cambiar[*]}"
clave_prueba="$(valor KEYCLOAK_TEST_USERS_PASSWORD '')"
[ "${#clave_prueba}" -lt 12 ] && aviso 'KEYCLOAK_TEST_USERS_PASSWORD tiene menos de 12 caracteres: la política del realm la rechaza'

agregar_al_env() { # agrega líneas al final del .env, separadas por una línea en blanco
  [ -s "$RUTA_ENV" ] && [ -n "$(tail -c1 "$RUTA_ENV")" ] && echo >> "$RUTA_ENV"
  printf '\n%s\n' "$1" >> "$RUTA_ENV"
}
# Llave de cifrado de coordenadas: en local se genera una si no existe (en Aiven debe ser la del equipo).
if [ -z "$(valor UBICACION_CLAVES '')" ] && ! grep -q '^UBICACION_CLAVES=' servicio-cientifico/.env 2>/dev/null; then
  [ "$PERFIL" = aiven ] && salir 'Falta UBICACION_CLAVES: con Aiven debe ser la misma llave del equipo (pídesela a quien cifró los datos).'
  agregar_al_env "# Llave para cifrar coordenadas en desarrollo local (la generó levantar.sh). No la cambies: los datos quedan cifrados con ella.
UBICACION_CLAVES=v1:$(head -c 32 /dev/urandom | base64 | tr -d '\n')
UBICACION_CLAVE_ACTIVA=v1"
  leer_env; aviso 'no había UBICACION_CLAVES: generé una llave local y la agregué al final del .env'
else
  bien 'llave de cifrado de coordenadas presente'
fi
# Secreto del cliente biohub-gateway de Keycloak (siempre en Docker): si falta se genera y más abajo se le pone al cliente.
if [ -z "$(valor KEYCLOAK_GATEWAY_CLIENT_SECRET '')" ]; then
  agregar_al_env "# Secreto del cliente biohub-gateway de Keycloak: lo generó levantar.sh.
KEYCLOAK_GATEWAY_CLIENT_SECRET=$(clave_al_azar 24)"
  leer_env; aviso 'no había KEYCLOAK_GATEWAY_CLIENT_SECRET: generé uno y lo agregué al final del .env'
fi
# Usuarios de migraciones de las bases locales: uno por servicio, distinto del de la app (como en Aiven).
if [ "$PERFIL" = local ]; then
  nuevas=""; nombres=()
  for k in CIENTIFICO EQUIPOS CONSULTA REPORTES GATEWAY; do
    [ -n "$(valor "${k}_MIGR_PASSWORD" '')" ] && continue
    [ -z "$(valor "${k}_MIGR_USER" '')" ] && nuevas+="${k}_MIGR_USER=${MIGRACION[$k]}"$'\n'
    nuevas+="${k}_MIGR_PASSWORD=$(clave_al_azar 18)"$'\n'; nombres+=("$k")
  done
  if [ -n "$nuevas" ]; then
    agregar_al_env "# Usuarios de migraciones (Flyway) de las bases locales: los generó levantar.sh.
${nuevas%$'\n'}"
    leer_env; aviso "no había contraseña de migraciones para ${nombres[*]}: las generé y las agregué al final del .env"
  else
    bien 'usuarios de migraciones de las bases locales presentes'
  fi
fi
[ "$(valor BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO "$(valor RABBITMQ_USER '')")" != "$(valor RABBITMQ_USER '')" ] &&
  aviso 'BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO no es igual a RABBITMQ_USER: el portal público rechazará los eventos'

# Todo lo del archivo pasa al entorno: lo heredan Docker, los servicios y las webs.
for k in "${!VARS[@]}"; do export "$k=${VARS[$k]}"; done
KC="$(valor KEYCLOAK_PORT 8180)"; GW="$(valor API_GATEWAY_PORT 8080)"
export SPRING_PROFILES_ACTIVE="$PERFIL"
[ -n "$(valor KEYCLOAK_ISSUER_URI '')" ] || export KEYCLOAK_ISSUER_URI="http://localhost:$KC/realms/biohub"
[ -n "$(valor KEYCLOAK_ADMIN_URL '')" ] || export KEYCLOAK_ADMIN_URL="http://localhost:$KC"
[ -n "$(valor SERVICIO_CIENTIFICO_URL '')" ] || export SERVICIO_CIENTIFICO_URL="http://localhost:$(valor SERVICIO_CIENTIFICO_PORT 8081)"
[ -n "$(valor SERVICIO_EQUIPOS_URL '')" ] || export SERVICIO_EQUIPOS_URL="http://localhost:$(valor SERVICIO_EQUIPOS_PORT 8082)"
[ -n "$(valor SERVICIO_CONSULTA_PUBLICA_URL '')" ] || export SERVICIO_CONSULTA_PUBLICA_URL="http://localhost:$(valor SERVICIO_CONSULTA_PUBLICA_PORT 8083)"
[ -n "$(valor SERVICIO_REPORTES_URL '')" ] || export SERVICIO_REPORTES_URL="http://localhost:$(valor SERVICIO_REPORTES_PORT 8084)"
[ -n "$(valor BIOHUB_GATEWAY_URL '')" ] || export BIOHUB_GATEWAY_URL="http://localhost:$GW"
# En local las migraciones usan el *_migr de cada base de Docker (que no gane uno de Aiven del .env de un servicio).
if [ "$PERFIL" = local ]; then
  for k in "${!MIGRACION[@]}"; do export "${k}_MIGR_USER=$(valor "${k}_MIGR_USER" "${MIGRACION[$k]}")" "${k}_MIGR_PASSWORD=$(valor "${k}_MIGR_PASSWORD" '')"; done
fi
export VITE_API_URL="http://localhost:$GW" VITE_KEYCLOAK_URL="http://localhost:$KC"

# ------------------------------------------------------------------------------------- 3. puertos libres
titulo '3/7 Revisando que los puertos estén libres'
previos="$(apagar_procesos)"; [ "$previos" -gt 0 ] && nota "apagué $previos procesos de una corrida anterior"
ocupados=()
for s in "${SERVICIOS[@]}"; do IFS='|' read -r nombre clave defecto _ <<< "$s"; p="$(valor "$clave" "$defecto")"; puerto_abierto "$p" && ocupados+=("$p ($nombre)"); done
for w in "${WEBS[@]}"; do IFS='|' read -r nombre p _ <<< "$w"; puerto_abierto "$p" && ocupados+=("$p ($nombre)"); done
if [ ${#ocupados[@]} -gt 0 ]; then mal "puertos ocupados por otro programa: ${ocupados[*]}"; salir 'Ciérralo (búscalo con: ss -ltnp | grep :<puerto>) y vuelve a correr.'; fi
bien 'puertos de servicios y webs libres'

# ------------------------------------------------------------------------------------- 4. Docker
titulo "4/7 Contenedores ($([ "$PERFIL" = local ] && echo 'bases en Docker' || echo 'bases en Aiven'))"
if [ "$REIMPORTAR" = 1 ]; then
  aviso 'reimportando el realm: se borran los datos de Keycloak de desarrollo'
  compose "$LOGS/docker-keycloak-rm.log" rm -sf keycloak; docker volume rm "${PROYECTO}_keycloak-data" >/dev/null 2>&1
fi
lista=(); for c in "${CONTENEDORES[@]}"; do IFS='|' read -r nombre _ _ _ solo <<< "$c"; [ -z "$solo" ] || [ "$solo" = "$PERFIL" ] && lista+=("$nombre"); done
nota 'docker compose up -d (la primera vez descarga imágenes: puede tardar varios minutos)'
compose "$LOGS/docker-up.log" up -d "${lista[@]}" rabbitmq-usuarios || { mal 'docker compose up falló:'; ultimas_lineas "$LOGS/docker-up.log"; salir 'Revisa el mensaje de arriba (suele ser una variable que falta o un puerto ocupado).'; }
for c in "${CONTENEDORES[@]}"; do
  IFS='|' read -r nombre clave defecto texto solo <<< "$c"; [ -n "$solo" ] && [ "$solo" != "$PERFIL" ] && continue
  limite=120; [ "$nombre" = keycloak ] && limite=240
  if seg="$(esperar "$texto" "$limite" '' contenedor_sano "$(nombre_contenedor "$nombre")")"; then
    bien "$(printf '%s puerto %-6s (%s s)' "$(ancho "$texto" 44)" "$(valor "$clave" "$defecto")" "$seg")"
  else mal "$texto no quedó sano. Mira: docker logs $(nombre_contenedor "$nombre")"; salir 'Sin la infraestructura los servicios no arrancan.'; fi
done
if [ "$PERFIL" = local ]; then
  # Se repite en cada arranque: una base creada antes queda corregida sin borrar datos (ver infra/mysql/initdb).
  todo_bien=1
  for c in mysql-transaccional mysql-lectura mysql-analitica mysql-gateway; do
    permisos_mysql "$c" || { todo_bien=0; mal "no se pudieron ajustar los usuarios de $c:"; printf '%s\n' "$PERMISOS_SALIDA" | while IFS= read -r l; do nota "$l"; done; }
  done
  [ "$todo_bien" = 1 ] && bien 'bases con usuario de datos y usuario de migraciones separados (sin ALL PRIVILEGES)' ||
    salir 'Revisa el mensaje de arriba (suele ser una contraseña con caracteres no permitidos en el .env).'
fi
if esperar 'usuarios de RabbitMQ' 90 '' contenedor_termino "$(nombre_contenedor rabbitmq-usuarios)" >/dev/null &&
   [ "$(estado_contenedor "$(nombre_contenedor rabbitmq-usuarios)" | cut -d'|' -f3)" = 0 ]; then
  bien 'usuarios de RabbitMQ por servicio creados'
else aviso "rabbitmq-usuarios no terminó bien: docker logs $(nombre_contenedor rabbitmq-usuarios)"; fi

URL_TOKEN="http://localhost:$KC/realms/biohub/protocol/openid-connect/token"
esperar 'el realm biohub' 120 '' responde "http://localhost:$KC/realms/biohub/.well-known/openid-configuration" issuer >/dev/null
if curl -fsS --max-time 8 -o /dev/null "$URL_TOKEN" --data-urlencode grant_type=password --data-urlencode client_id=biohub-pruebas \
     --data-urlencode username=admin1 --data-urlencode "password=$(valor KEYCLOAK_TEST_USERS_PASSWORD '')"; then
  bien 'usuarios de prueba entran con la contraseña del .env'
else aviso 'los usuarios de prueba NO entran con la contraseña del .env (el realm se importó con otra).'; nota 'Arréglalo con:  ./levantar.sh --reimportar-realm'; fi
gateway_entra() { curl -fsS --max-time 8 -o /dev/null "$URL_TOKEN" --data-urlencode grant_type=client_credentials   --data-urlencode client_id=biohub-gateway --data-urlencode "client_secret=$(valor KEYCLOAK_GATEWAY_CLIENT_SECRET '')"; }
# Token de administración (realm master): lo usan el secreto del gateway y el tema del login.
token_admin="$(curl -fsS --max-time 8 "http://localhost:$KC/realms/master/protocol/openid-connect/token" --data-urlencode grant_type=password   --data-urlencode client_id=admin-cli --data-urlencode "username=$(valor KEYCLOAK_ADMIN_USER '')" --data-urlencode "password=$(valor KEYCLOAK_ADMIN_PASSWORD '')" 2>/dev/null |
  sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
if gateway_entra; then
  bien 'cliente del gateway listo (administración de usuarios)'
else
  # El secreto solo se toma al importar el realm la primera vez: se le pone al cliente el del .env, sin reimportar.
  id_cliente=""
  [ -n "$token_admin" ] && id_cliente="$(curl -fsS --max-time 8 -H "Authorization: Bearer $token_admin"     "http://localhost:$KC/admin/realms/biohub/clients?clientId=biohub-gateway" 2>/dev/null | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')"
  [ -n "$id_cliente" ] && curl -fsS --max-time 8 -X PUT -H "Authorization: Bearer $token_admin" -H 'Content-Type: application/json'     -d "{\"clientId\":\"biohub-gateway\",\"secret\":\"$(valor KEYCLOAK_GATEWAY_CLIENT_SECRET '')\"}"     "http://localhost:$KC/admin/realms/biohub/clients/$id_cliente" -o /dev/null
  if gateway_entra; then bien 'cliente del gateway listo: le puse en Keycloak el secreto del .env'
  elif [ -z "$id_cliente" ]; then aviso 'tu Keycloak no tiene el cliente biohub-gateway (se importó antes de que existiera): la sala Usuarios fallará.'
    nota 'Arréglalo con:  ./levantar.sh --reimportar-realm   (borra los usuarios que hayas creado a mano en Keycloak)'
  else aviso 'el cliente biohub-gateway no acepta el secreto del .env: la sala Usuarios fallará.'; nota 'Arréglalo con:  ./levantar.sh --reimportar-realm'; fi
fi
# Tema de inicio de sesión de BioHub (D64): un realm importado antes no lo tiene; se activa por la API de administración.
if [ -n "$token_admin" ]; then
  if curl -fsS --max-time 8 -H "Authorization: Bearer $token_admin" "http://localhost:$KC/admin/realms/biohub" | grep -q '"loginTheme":"biohub"'; then
    bien 'tema de inicio de sesión de BioHub'
  elif curl -fsS --max-time 8 -X PUT -H "Authorization: Bearer $token_admin" -H 'Content-Type: application/json'          -d '{"loginTheme":"biohub"}' "http://localhost:$KC/admin/realms/biohub" -o /dev/null; then
    bien 'tema de inicio de sesión de BioHub activado'
  fi
else aviso 'no se pudo activar el tema de inicio de sesión (no impide entrar): revisa KEYCLOAK_ADMIN_USER y KEYCLOAK_ADMIN_PASSWORD'; fi
unset token_admin

# ------------------------------------------------------------------------------------- 5. compilar
titulo '5/7 Compilando los servicios'
hay_que_compilar=$COMPILAR
for s in "${SERVICIOS[@]}"; do
  IFS='|' read -r nombre _ _ _ <<< "$s"
  jar="$(ls "$nombre"/target/*.jar 2>/dev/null | grep -v original | head -1)"
  if [ -z "$jar" ] || [ -n "$(find "$nombre/src/main" "$nombre/pom.xml" pom.xml -newer "$jar" -type f 2>/dev/null | head -1)" ]; then hay_que_compilar=1; break; fi
done
if [ "$hay_que_compilar" = 1 ]; then
  nota 'mvn -DskipTests package (1 a 3 minutos; las pruebas se corren aparte con mvn test)'; t0=$(date +%s)
  mvn -q -B -DskipTests package > "$LOGS/compilacion.log" 2>&1 || { mal 'la compilación falló:'; ultimas_lineas "$LOGS/compilacion.log" 40; salir "Log completo: $LOGS/compilacion.log"; }
  bien "compilado en $(( $(date +%s) - t0 )) s"
else bien 'sin cambios desde la última compilación (usa --compilar para forzarla)'; fi

# ------------------------------------------------------------------------------------- 6. servicios
titulo "6/7 Arrancando los servicios (perfil $PERFIL)"
: > "$ESTADO_ARCHIVO"
for s in "${SERVICIOS[@]}"; do
  IFS='|' read -r nombre clave defecto texto <<< "$s"
  jar="$(ls "$RAIZ/$nombre"/target/*.jar | grep -v original | head -1)"
  # Se arranca desde la carpeta del servicio para que lea ../.env, como en el README.
  iniciar "$nombre" "$RAIZ/$nombre" java -Xms64m -Xmx512m -jar "$jar"
  nota "$texto arrancando en el puerto $(valor "$clave" "$defecto")"
done
for w in "${WEBS[@]}"; do
  IFS='|' read -r nombre p texto <<< "$w"
  if [ ! -f "$nombre/node_modules/.package-lock.json" ] || [ "$nombre/package-lock.json" -nt "$nombre/node_modules/.package-lock.json" ]; then
    nota "$texto: instalando dependencias (npm ci)"
    ( cd "$nombre" && npm ci --no-audit --no-fund > "$LOGS/$nombre-npm.log" 2>&1 ) || { mal "npm ci falló en $nombre"; ultimas_lineas "$LOGS/$nombre-npm.log"; }
  fi
  iniciar "$nombre" "$RAIZ/$nombre" npm run dev
  nota "$texto arrancando en el puerto $p"
done

explicar_caida() {
  [ -f "$1" ] || return 0
  grep -E 'Caused by: |APPLICATION FAILED TO START|Description:|ERROR' "$1" | grep -v 'Caused by: org.springframework' | tail -3 | while IFS= read -r l; do nota "$l"; done
  nota "log completo: $1"
}
fallidos=()
for s in "${SERVICIOS[@]}"; do
  IFS='|' read -r nombre clave defecto texto <<< "$s"; p="$(valor "$clave" "$defecto")"
  seg="$(esperar "$texto" 240 "$(pid_de "$nombre")" responde "http://127.0.0.1:$p/actuator/health" '"UP"')"; r=$?
  if [ $r = 0 ]; then bien "$(printf '%s puerto %-6s (%s s)' "$(ancho "$texto" 44)" "$p" "$seg")"
  elif [ $r = 2 ]; then mal "$texto se cayó al arrancar:"; explicar_caida "$LOGS/$nombre.log"; fallidos+=("$nombre")
  else mal "$texto no respondió en el puerto $p en 4 minutos:"; explicar_caida "$LOGS/$nombre.log"; fallidos+=("$nombre"); fi
done
# Historial de registro append-only (D60, ASR7): con las tablas ya creadas por Flyway, el usuario de la app queda con
# solo INSERT y SELECT sobre hallazgo_version y hallazgo_auditoria. Se comprueba intentando borrar con esa credencial.
if [ "$PERFIL" = local ] && [[ ! " ${fallidos[*]} " =~ " servicio-cientifico " ]]; then
  if permisos_mysql mysql-transaccional; then
    historial="$(printf '%s\n' "$PERMISOS_SALIDA" | grep '^historial' | head -1)"
    case "$historial" in 'historial protegido'*) bien "$historial" ;; *) aviso "$historial" ;; esac
  else mal 'el historial NO quedó protegido:'; printf '%s\n' "$PERMISOS_SALIDA" | while IFS= read -r l; do nota "$l"; done; fi
fi

# ------------------------------------------------------------------------------------- 7. webs
titulo '7/7 Webs'
for w in "${WEBS[@]}"; do
  IFS='|' read -r nombre p texto <<< "$w"
  if seg="$(esperar "$texto" 90 "$(pid_de "$nombre")" responde "http://localhost:$p/")"; then bien "$(printf '%s puerto %-6s (%s s)' "$(ancho "$texto" 44)" "$p" "$seg")"
  else mal "$texto no respondió. Últimas líneas de $LOGS/$nombre.log :"; ultimas_lineas "$LOGS/$nombre.log" 15; fallidos+=("$nombre"); fi
done

if [ "$SEMBRAR" = 1 ]; then
  titulo 'Datos de ejemplo'
  if [ "$PERFIL" != local ]; then aviso 'no siembro datos con --perfil aiven: la base es compartida'
  elif [ ${#fallidos[@]} -gt 0 ]; then aviso 'no siembro datos: hay servicios caídos'
  else BIOHUB_GATEWAY="http://localhost:$GW" BIOHUB_KEYCLOAK="http://localhost:$KC" BIOHUB_CLAVE_PRUEBA="$(valor KEYCLOAK_TEST_USERS_PASSWORD '')" \
         node "$RAIZ/scripts/sembrar-demo.mjs"; fi
fi

mostrar_tabla
mostrar_usuarios
titulo 'Listo'
echo "   ${V}Web interna (con login):  http://localhost:5173${N}"
echo "   ${V}Web pública (sin login):  http://localhost:5174${N}"
nota ''
nota "Logs de cada servicio: $LOGS"
nota 'Ver el estado:       ./levantar.sh --estado'
nota 'Apagar todo:         ./levantar.sh --detener'
nota 'Datos de ejemplo:    ./levantar.sh --sembrar'
nota "Tardó $(( $(date +%s) - INICIO )) s."
echo
[ "${CAIDOS:-0}" -gt 0 ] && exit 1
exit 0
