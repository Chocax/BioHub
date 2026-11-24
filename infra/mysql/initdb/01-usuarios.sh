#!/bin/bash
# Usuarios de MySQL en desarrollo local, con los mismos permisos que en Aiven (D39, SEC-11):
#   <base>_app   SELECT, INSERT, UPDATE, DELETE           (lo usa el servicio)
#   <base>_migr  lo anterior + CREATE, ALTER, DROP, INDEX, REFERENCES   (lo usa solo Flyway)
# Ninguno tiene ALL PRIVILEGES ni GRANT OPTION, y cada uno solo ve su base.
#
# Cada contenedor dice qué bases tiene con variables numeradas (ver docker-compose.yml):
#   BIOHUB_BASE_n, BIOHUB_APP_USER_n, BIOHUB_APP_PASSWORD_n, BIOHUB_MIGR_USER_n, BIOHUB_MIGR_PASSWORD_n
#
# Si además está montado /biohub/historial.sql (solo en mysql-transaccional) y Flyway ya creó las tablas de
# registro, se bloquea el historial: el usuario de la app queda con solo INSERT y SELECT sobre hallazgo_version y
# hallazgo_auditoria (D60, RNF10, ASR7). Es el mismo archivo que se ejecuta en Aiven.
#
# MySQL lo ejecuta una vez, al crear el volumen. Se puede repetir sin riesgo (levantar.ps1 lo corre en cada
# arranque): así una base creada antes, con el usuario de ALL PRIVILEGES, queda corregida sin borrar datos.
set -euo pipefail

export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"
sql() { mysql --protocol=socket -uroot "$@"; }

seguro() { # evita romper el SQL o inyectar algo por una variable
  if [[ ! "$2" =~ ^[A-Za-z0-9_.@#%+=-]+$ ]]; then
    echo "[biohub] ERROR: $1 está vacía o tiene caracteres no permitidos (use letras, números y _.@#%+=-)" >&2
    return 1
  fi
}

for n in 1 2 3; do
  base_var="BIOHUB_BASE_$n"
  base="${!base_var:-}"
  [ -z "$base" ] && continue
  app_var="BIOHUB_APP_USER_$n";      app="${!app_var:-}"
  appc_var="BIOHUB_APP_PASSWORD_$n"; appc="${!appc_var:-}"
  migr_var="BIOHUB_MIGR_USER_$n";    migr="${!migr_var:-}"
  migrc_var="BIOHUB_MIGR_PASSWORD_$n"; migrc="${!migrc_var:-}"
  seguro "$base_var" "$base"; seguro "$app_var" "$app"; seguro "$appc_var" "$appc"
  seguro "$migr_var" "$migr"; seguro "$migrc_var" "$migrc"
  if [ "$app" = "$migr" ]; then
    echo "[biohub] ERROR: el usuario de la app y el de migraciones de '$base' deben ser distintos" >&2
    exit 1
  fi

  sql <<SQL
CREATE DATABASE IF NOT EXISTS \`${base}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${app}'@'%' IDENTIFIED BY '${appc}';
CREATE USER IF NOT EXISTS '${migr}'@'%' IDENTIFIED BY '${migrc}';
ALTER USER '${app}'@'%' IDENTIFIED BY '${appc}' WITH MAX_USER_CONNECTIONS 20;
ALTER USER '${migr}'@'%' IDENTIFIED BY '${migrc}' WITH MAX_USER_CONNECTIONS 3;
REVOKE IF EXISTS ALL PRIVILEGES ON \`${base}\`.* FROM '${app}'@'%';
REVOKE IF EXISTS GRANT OPTION ON \`${base}\`.* FROM '${app}'@'%';
REVOKE IF EXISTS ALL PRIVILEGES ON \`${base}\`.* FROM '${migr}'@'%';
REVOKE IF EXISTS GRANT OPTION ON \`${base}\`.* FROM '${migr}'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${base}\`.* TO '${app}'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON \`${base}\`.* TO '${migr}'@'%';
SQL
  # Permisos viejos con otro nombre de base: la imagen oficial de MySQL (MYSQL_USER) daba ALL PRIVILEGES sobre
  # `consulta\_publica` (guion bajo escapado), que no es la misma entrada que `consulta_publica`.
  sql -N -r -e "SELECT CONCAT('REVOKE IF EXISTS ALL PRIVILEGES ON \`', Db, '\`.* FROM ''', User, '''@''', Host, ''';',
                           'REVOKE IF EXISTS GRANT OPTION ON \`', Db, '\`.* FROM ''', User, '''@''', Host, ''';')
             FROM mysql.db WHERE User IN ('${app}', '${migr}') AND Db <> '${base}'" | sql
  # Permisos que hayan quedado tabla por tabla de una corrida anterior del bloqueo del historial
  sql -N -r -e "SELECT CONCAT('REVOKE IF EXISTS ALL PRIVILEGES ON \`', Db, '\`.\`', Table_name, '\` FROM ''', User, '''@''%'';')
             FROM mysql.tables_priv WHERE Db = '${base}' AND User = '${app}' AND Host = '%'" | sql
  echo "[biohub] base ${base}: ${app} (datos) y ${migr} (migraciones)"
done

if [ -f /biohub/historial.sql ]; then
  app="${BIOHUB_APP_USER_1}"
  hay=$(sql -N -e "SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = 'cientifico' AND table_name IN ('hallazgo_version', 'hallazgo_auditoria')")
  if [ "$hay" = "2" ]; then
    contenido=$(cat /biohub/historial.sql)
    # El archivo de Aiven nombra al usuario cientifico_app; en local puede llamarse distinto.
    printf '%s\n' "${contenido//\'cientifico_app\'/\'${app}\'}" | sql --force cientifico
    # Comprobación: con la credencial de la app, borrar del historial debe dar ERROR 1142 (sin permiso).
    prueba=$(MYSQL_PWD="${BIOHUB_APP_PASSWORD_1}" mysql --protocol=socket -u"${app}" cientifico \
               -e "DELETE FROM hallazgo_version LIMIT 0" 2>&1 || true)
    if [[ "$prueba" == *1142* ]]; then
      echo "[biohub] historial protegido: ${app} no puede modificar ni borrar hallazgo_version ni hallazgo_auditoria"
    else
      echo "[biohub] ERROR: el historial NO quedó protegido para ${app}" >&2
      exit 1
    fi
  else
    echo "[biohub] historial pendiente: Flyway todavía no crea las tablas de registro"
  fi
fi
