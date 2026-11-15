# pruebas-carga

Pruebas de carga de BioHub con [k6](https://k6.io) sobre un **Compose efímero y local** (D30: nunca contra Aiven ni contra
un despliegue). Miden la consulta pública (ASR1/RNF1, pico x60) y su comportamiento mientras el lado científico escribe
(ASR4/RNF5). Los criterios para que una corrida cuente como evidencia están en la decisión **D42**
(`docs/equipo/03-arquitectura.md`); este README resume lo que hay que hacer para cumplirlos.

## Qué hay

| Archivo | Para qué sirve |
|---|---|
| `construir-imagen.sh` | Construye `biohub/servicio-consulta-publica:carga` a partir del jar ya compilado (`mvn -DskipTests package` en `servicio-consulta-publica/`). No toca el Dockerfile ni `target/`. |
| `compose-carga.yml` | Compose **efímero**: N réplicas de `servicio-consulta-publica` (con límite de CPU y memoria) detrás de NGINX (`nginx-carga.conf`, puerto `127.0.0.1:18080`). Reutiliza `mysql-lectura`, `redis` y `rabbitmq` del compose raíz (red `biohub_default`), que deben estar arriba. |
| `datos/sembrar.sh` + `semilla-fichas.sql` | Siembra fichas sintéticas en `mysql-lectura` (UUID `c0a9a000-0000-4000-8000-<12 hex>`, sin coordenadas, 20 % `EN_PELIGRO`; 20 géneros/17 familias, 30 epítetos y 33 departamentos). **2.000 por defecto**; `FICHAS=10000 ./datos/sembrar.sh` cambia el volumen (1 a 99999). Idempotente. |
| `datos/limpiar.sh` | Borra lo que dejó la carga: fichas con prefijo `c0a9a000`, claves de caché `biohub:publica:*` en Redis y, en RabbitMQ, el exchange temporal `biohub.eventos.carga` y la cola de carga con su DLQ. No toca la cola ni la DLQ de desarrollo. |
| `correr-k6.sh` | Ejecuta un script k6 con la imagen `grafana/k6` (versión fija en `K6_VERSION`) y deja el JSON en `resultados/`. |
| `k6/consulta-publica-pico.js` | **ASR1/RNF1.** Perfil de tasa constante: base N req/s, rampa, pico N x `MULT` (60 por defecto), bajada y post-pico. Mide % de 200, 5xx/timeouts, p95 por fase y por ventana de 15 s, y la estabilización tras la rampa. Mezcla 60 % fichas (85 % "virales", caché caliente) y 40 % búsquedas. |
| `k6/carga-cientifica-simultanea.js` | **ASR4/RNF5 (CG-04).** Mide la lectura pública con y sin carga científica (`CIENTIFICA=0|1`). La carga son eventos `hallazgo.registrado`/`hallazgo.reclasificado` publicados a RabbitMQ y consumidos por consulta-publica (SELECT ... FOR UPDATE + INSERT/UPDATE e invalidación de caché). Al final comprueba que la **DLQ de carga esté vacía** (umbral `dlq_mensajes`) y reporta eventos publicados, procesados (ack) y pendientes. |
| `k6/gateway-limite-api-externa.js` | **ASR8/RNF11, HU-08 c.2, HU-12 c.2.** Va contra el **api-gateway**, no contra NGINX. Un consumidor abusivo (A) recibe 429 con `Retry-After` y como máximo 60 respuestas 200 por minuto. Mientras tanto, otros dos consumidores (uno con límite configurado) y el portal siguen en 200 sin perder latencia. Además, una key revocada a mitad de la prueba recibe 401 desde la petición siguiente. Ver la sección siguiente. |
| `k6/busqueda-publica-fria.js` + `correr-hu06-frio.sh` + `resumir-hu06.sh` + `datos/vaciar-cache.sh` | **HU-06 c.3 / CG-02 (RNF5).** Búsqueda del portal con 10.000 fichas y la caché vacía: fase A (100 búsquedas distintas, 1 VU) y fase B (100 distintas, 10 VUs); el script vacía `biohub:publica:*` antes de cada fase y `resumir-hu06.sh` calcula la mediana de las corridas. Ver la sección "HU-06 c.3 / CG-02". |
| `k6/registro-hallazgo.js` | **Pendiente** (depende de HU-01, módulo registro). Esqueleto para RNF2/RNF4. |
| `k6/lib/comun.js` | Utilidades compartidas (mezcla de peticiones, ids de la semilla, ventanas). |
| `resultados/` | JSON de cada corrida. Los que empiezan por `descartado-` documentan fallos del banco de pruebas y **no cuentan como evidencia** (D42). |

## Cómo correrlo

Requisitos: Docker, el `.env` de la raíz y la infraestructura del compose raíz arriba (`docker compose up -d`: MySQL de
lectura, Redis, RabbitMQ, Keycloak). Todos los comandos, desde `pruebas-carga/`:

```bash
# 1. Jar e imagen de la consulta pública
(cd ../servicio-consulta-publica && mvn -DskipTests package)
./construir-imagen.sh

# 2. Compose de carga (elige las réplicas) y esperar a que responda
docker compose -p biohub-carga -f compose-carga.yml --env-file ../.env up -d --scale consulta-publica=2
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/publica/fichas     # 200

# 3. Datos de carga
./datos/sembrar.sh

# 4a. Pico x60 (ASR1). Con N=20 el pico es de 1.200 req/s
REPLICAS=2 CPUS=2 ./correr-k6.sh k6/consulta-publica-pico.js -e N=20 -e SALIDA=/resultados/asr1-2replicas-n20.json

# 4b. Carga científica simultánea (ASR4): el par sin / con carga
./correr-k6.sh k6/carga-cientifica-simultanea.js -e CIENTIFICA=0 -e SALIDA=/resultados/asr4-sin.json
./correr-k6.sh k6/carga-cientifica-simultanea.js -e CIENTIFICA=1 -e SALIDA=/resultados/asr4-con.json
#     humo corto: -e R=10 -e EV=5 -e DUR=15s

# 5. Apagar y limpiar (en este orden: limpiar.sh borra la cola de carga, que las réplicas no deben seguir usando)
docker compose -p biohub-carga -f compose-carga.yml --env-file ../.env down -v
./datos/limpiar.sh
```

Parámetros de `consulta-publica-pico.js`: `BASE_URL` (por defecto `http://127.0.0.1:18080`), `N`, `MULT`, `T_BASE`, `T_RAMP`,
`T_PICO`, `T_BAJA`, `T_POST`, `MAX_VUS`, `POOL`, `SALIDA`. De `carga-cientifica-simultanea.js`: `R` (req/s públicas),
`EV` (eventos/s), `DUR`, `CIENTIFICA`, `COLA`, `RMQ_URL`, `SALIDA`. Variables del compose: `CARGA_CPUS` (CPU por réplica,
2 por defecto) y `COLA_CARGA` (cola de carga, `consulta-publica.carga.eventos`).

### HU-06 c.3 / CG-02: búsqueda en frío con 10.000 fichas

Mide el criterio 3 de HU-06.4 (RNF5): con 10.000 fichas en `mysql-lectura` y la caché vacía, el p95 de la búsqueda pública es
<= 2 s. Va **directo al servicio por NGINX** (no por el gateway: con su límite de 120/min por IP mediría el limitador, riesgo RQ-6).

```bash
# 1. Jar, imagen y compose de carga con UNA réplica (CARGA_CPUS = CPU por réplica, 2 por defecto; mem_limit 1g)
(cd ../servicio-consulta-publica && mvn -DskipTests package) && ./construir-imagen.sh
CARGA_CPUS=2 docker compose -p biohub-carga -f compose-carga.yml --env-file ../.env up -d --scale consulta-publica=1
# 2. 10.000 fichas (comprueba el conteo con SQL; correr-hu06-frio.sh también lo exige)
FICHAS=10000 ./datos/sembrar.sh
# 3. Una corrida = calentamiento + [vaciar caché, fase A] + [vaciar caché, fase B]. RUN es también la semilla de las búsquedas
for r in 1 2 3; do RUN=$r ./correr-hu06-frio.sh; done
./resumir-hu06.sh            # tabla y resultados/hu06-resumen-medianas.json (mediana de las 3)
# 4. Apagar y limpiar (en este orden, como arriba)
docker compose -p biohub-carga -f compose-carga.yml --env-file ../.env down -v
./datos/limpiar.sh
```

- **Caché fría:** `datos/vaciar-cache.sh` hace `SCAN` + `DEL` solo de `biohub:publica:*` (nunca `FLUSHALL`: el Redis es el del compose raíz,
  compartido con el desarrollo) y falla si quedan claves. La clave de caché incluye q, familia, departamento, page y size, así que
  100 búsquedas distintas son 100 fallos de caché; el propio script lo comprueba: al vaciar antes de la fase siguiente hay exactamente
  100 claves (una por búsqueda). Antes de la fase A se hacen 24 peticiones de calentamiento con `size` 7/11/13 (ninguna coincide con las
  medidas) y se vacía la caché; `WARMUP=0` lo omite.
- **Las 200 búsquedas** (100 por fase, sin repetirse entre fases ni dentro de ellas) las genera `busqueda-publica-fria.js` con la `SEED`
  (= `RUN`), por cuotas por fase: q por género (10), epíteto (12), género+epíteto (12), prefijo (8), nombre común (6), familia (8),
  departamento por código o nombre (8), q+familia (8), q+departamento (8), familia+departamento (6), páginas profundas hasta el tope de
  10.000 (8), prefijos del número de ficha (4) y q de 2 letras (`LIKE`, 2). El JSON lista cada búsqueda con su latencia, su `total` y los
  elementos devueltos.
- **Umbrales** (por fase; `umbrales_cumplidos` en el JSON y código de salida 99 de k6 si no): p95 <= 2000 ms, 0 respuestas 5xx o sin
  respuesta, 100 % de 200, 100 iteraciones, **ninguna búsqueda vacía** (`total` > 0 y página con elementos) y ninguna respuesta con
  `modoDegradado: true` (no mediría la búsqueda completa).
- **D42:** `parametros` registra fase, VUs, semilla, fichas sembradas, réplicas y CPU/memoria **leídas del contenedor en marcha**, commit
  (`-sin-commit` si el árbol tiene cambios sin commitear), red de k6 (`biohub_default`) y fecha. Los JSON son
  `resultados/hu06-corrida<N>-fase-{a,b}.json` y la mediana `hu06-resumen-medianas.json`. Los `hu06-informativo-*` y
  `hu06-control-*` son controles (JVM recién reiniciada y sin calentamiento; las mismas búsquedas con la caché caliente) y **no entran**
  en la mediana. Si una corrida falla por el banco de pruebas, renómbrala a `descartado-...` y explica el motivo aquí.
- **Alcance:** mide `servicio-consulta-publica` + NGINX + `mysql-lectura` + Redis locales, en una misma máquina compartida con k6 (que va en
  la red de Docker). MySQL y Redis son los del compose raíz (sin límites de CPU/memoria). No incluye el gateway ni la red de un despliegue.
  Los límites de la réplica (2 CPU y 1 GiB) son los de `compose-carga.yml` (ARQ-24): ningún compose de despliegue del repositorio declara
  `cpus`/`mem_limit` (el de `deploy/backend/` se retiró con IT-01).

### ASR8: límite por consumidor en el gateway

Requisitos:

- El `api-gateway` y `servicio-consulta-publica` corriendo con el perfil **`local`**. **Nunca con `aiven`**: el script crea 4 consumidores con prefijo `k6-asr8-` en la base del gateway (D30). Al final revoca sus keys.
- Redis y Keycloak del compose arriba.
- El JWT de `admin1` en la variable `TOKEN`. Vive 5 minutos, así que sácalo justo antes. La prueba dura unos 4 minutos.

```bash
# Linux / Git Bash (k6 en la red del host)
export TOKEN=$(curl -s -d grant_type=password -d client_id=biohub-pruebas -d username=admin1 \
  --data-urlencode "password=$(grep -E '^KEYCLOAK_TEST_USERS_PASSWORD=' ../.env | cut -d= -f2-)" \
  http://localhost:8180/realms/biohub/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
./correr-k6.sh k6/gateway-limite-api-externa.js -e BASE_URL=http://127.0.0.1:8080 -e SALIDA=/resultados/asr8-gateway.json
```

```powershell
# Windows (Docker Desktop: el contenedor llega al gateway por host.docker.internal)
cd D:\Projects\BioHub
$clave = (Select-String -Path .env -Pattern '^KEYCLOAK_TEST_USERS_PASSWORD=').Line.Split('=', 2)[1]
$env:TOKEN = (Invoke-RestMethod -Method Post http://localhost:8180/realms/biohub/protocol/openid-connect/token -Body @{ grant_type = 'password'; client_id = 'biohub-pruebas'; username = 'admin1'; password = $clave }).access_token
docker run --rm -e TOKEN -v "${PWD}\pruebas-carga\k6:/scripts:ro" -v "${PWD}\pruebas-carga\resultados:/resultados" grafana/k6:2.3.0 run --quiet -e BASE_URL=http://host.docker.internal:8080 -e SALIDA=/resultados/asr8-gateway.json /scripts/gateway-limite-api-externa.js
```

Parámetros:

| Parámetro | Por defecto | Qué es |
|---|---|---|
| `ABUSO_RPS` | 20 | Peticiones por segundo del consumidor abusivo |
| `LIMITE_B` | 120 | Límite configurado del consumidor B, por minuto |
| `LIMITE_POR_DEFECTO` | 60 | Debe ser igual al del gateway |
| `T_BASE`, `T_ABUSO`, `T_POST` | 60s, 120s, 30s | Duración de cada fase |
| `SALIDA` | – | Ruta del JSON de resultados |

**Qué tiene que pasar para que cuente:**

- Consumidor A: a lo sumo `60 × (ceil(T_ABUSO/60) + 1)` respuestas 200 (por la ventana fija, D46), al menos un 429, todos los 429 con `Retry-After` y ningún otro estado.
- B, C y el portal: 100 % de 200.
- D: 100 % de 401 después de revocar su key.

`umbrales_cumplidos: true` en el JSON resume todo. Los cinco clientes salen de la misma IP, por eso D pide a 0,5 req/s: así queda debajo del bloqueo por IP de 20 keys inválidas por minuto.

### Eventos de la carga científica y la verificación del publicador

consulta-publica verifica el `user_id` AMQP de todo `hallazgo.*` (ARQ-13, SEC-J-01) y **esa verificación no se desactiva
para la carga**. Por eso:

- k6 publica con `user_id` = `RMQ_USER` (el usuario de RabbitMQ con el que se autentica; el broker rechaza un `user_id`
  distinto) y `compose-carga.yml` define `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO` = `RABBITMQ_USER`.
- `version` va entre 1 y 1.000.000 (`DatosHallazgo.VERSION_MAXIMA`) y crece 1 cada 4 s por hallazgo, para no disparar la
  alerta de salto anómalo.
- Las réplicas de carga consumen una cola propia (`consulta-publica.carga.eventos`, DLQ `...carga.eventos.dlq`), no la de
  desarrollo. Si la DLQ de carga termina con mensajes, la prueba falla; la causa suele ser un `user_id` distinto, una
  versión fuera de rango o un campo inválido (consulta-publica registra el motivo en su log).

## Secretos

- No hay credenciales en los scripts ni en `resultados/`: todo sale del `.env` en tiempo de ejecución.
- Ninguna contraseña ni JWT viaja como argumento (se vería con `ps`): `correr-k6.sh` pasa `RMQ_USER`/`RMQ_PASS` por
  variable de entorno; para `registro-hallazgo.js` haz `export TOKEN=<jwt>` (no `-e TOKEN=...`). `sembrar.sh` y
  `limpiar.sh` pasan `MYSQL_PWD`, `REDISCLI_AUTH` y las credenciales de RabbitMQ por entorno o por stdin.
- La imagen de k6 está fijada (`grafana/k6:2.3.0`; cámbiala con `K6_VERSION`), no `latest`.
- Solo local: NGINX y las réplicas escuchan en `127.0.0.1` o en la red de Docker.

## Qué guardar con cada corrida (D42)

Una corrida cuenta como evidencia de ASR1/ASR4 solo si su JSON registra, en `parametros`:

| Metadato | Cómo se registra |
|---|---|
| N y multiplicador | `-e N=... -e MULT=...` (el script los guarda como `N`, `MULT`, `pico_req_s`) |
| Réplicas | `REPLICAS=<n>` (el mismo valor de `--scale consulta-publica=<n>`) antes de `./correr-k6.sh` |
| CPU por réplica | `CPUS=<n>` (el mismo valor de `CARGA_CPUS` del compose) |
| Commit | automático (`git rev-parse --short HEAD`; añade `-sin-commit` si el árbol tiene cambios sin commitear) |
| Red de k6 | automático (`RED_K6`, `host` por defecto; `RED_K6=biohub_default` para correr dentro de la red del compose) |

Además:

1. **Éxito** = 200 / (peticiones + iteraciones descartadas). El JSON lo da en `exito_d42_pct`; la carga que k6 no pudo
   emitir cuenta como fallo.
2. **Estabilización** como en `consulta-publica-pico.js`: ventanas de 15 s con p95 < 2 s desde el fin de la rampa
   (`estabilizacion_s`, `ventanas`).
3. Corre siempre contra este Compose efímero, nunca contra Aiven (D30).
4. Si el banco de pruebas falla (NGINX sin descriptores, k6 sin VUs, etc.), renombra el JSON con prefijo `descartado-` y
   documenta el motivo: no cuenta.
5. ASR1 "demostrado" exige además una corrida donde se active la degradación (contando las respuestas `modoDegradado: true`)
   y la comparación de 1 y 2 réplicas en un punto donde una sola no alcance. ASR4 exige el par `CIENTIFICA=0` / `CIENTIFICA=1`
   y, en el segundo, DLQ en 0 y eventos procesados igual a los publicados.

## Reglas

- Ejecuta las pruebas solo contra el entorno local de pruebas.
- No guardes contraseñas ni tokens en los scripts: léelos de variables de entorno.
- Los eventos de carga nunca deben incluir coordenadas ni datos reales (la semilla es sintética).
