# Revisión DevOps 2026-11-19 (develop, HEAD 8d23981)

Solo lectura. Se revisaron README, `docs/equipo/09-despliegue.md`, `docker-compose.yml`, `.env.example` (raíz y de servicio), `levantar.ps1`/`.cmd`, Dockerfiles, `.github/workflows/ci.yml` y los `application*.yml`. No se levantó ni paró nada; `docker compose config --quiet` pasó sin errores (con el `.env` local).

**Calificación: 6,5 / 10.** Infra local y CI sólidos; el hueco está en Linux/macOS, en la documentación desfasada y en que la Entrega 3 (despliegue) no tiene ningún artefacto.

Estado CI en develop (`gh run list`): últimas 5 ejecuciones: 4 success y 1 cancelled (PR #14, cancelada por `cancel-in-progress`, no es fallo). La de HEAD (PR #17) pasó en 13m20s.

## Hallazgos

### OPS-R-1 (Alto) No hay equivalente de `levantar.ps1` para Linux/macOS
- Archivos: `levantar.ps1`, `levantar.cmd`, `README.md:84-113`.
- Problema: el "atajo de un solo comando" solo existe para Windows (PowerShell/cmd). En Linux/macOS (el equipo documenta Ubuntu en README:39) el README ofrece 7 terminales manuales (§4 b-e). El script PowerShell además hace cosas que la ruta manual no documenta: generar `UBICACION_CLAVES`, esperar healthchecks, comprobar usuarios del realm, compilar solo si hay cambios, sembrar.
- Importa: en la sustentación o en la máquina de un evaluador con Linux, levantar el sistema es lento y propenso a errores. Se pide "un solo comando" a nivel DevOps.
- Corrección: añadir `levantar.sh` (bash) con los mismos parámetros, o un `Makefile` (`make up`, `make down`, `make seed`) más `scripts/sembrar-demo.mjs` (ya es Node, portable). Alternativa mayor: perfil `app` en el compose que levante gateway y servicios con los Dockerfiles existentes (ver OPS-R-4).
- Esfuerzo: M.

### OPS-R-2 (Alto) El README manual (Linux) no arranca tal cual: perfil Spring y `UBICACION_CLAVES`
- Archivos: `README.md:156-164`, `.env.example` (raíz, sin `UBICACION_*`), `docs/equipo/09-despliegue.md:36-38,226-228`.
- Problema A: README:156 dice que solo `servicio-cientifico` y `servicio-consulta-publica` necesitan `SPRING_PROFILES_ACTIVE`; 09-despliegue §2 y §7 dicen que los cinco (también `api-gateway`, `servicio-equipos`, `servicio-reportes`) no arrancan sin perfil ("no hay URL ni credenciales por defecto"). El README manda arrancar gateway/equipos/reportes sin perfil.
- Problema B: `.env.example` de la raíz no contiene `UBICACION_CLAVES`/`UBICACION_CLAVE_ACTIVA` (están solo en `servicio-cientifico/.env.example:58-59`). Quien siga el README en Linux (`cp .env.example .env`) tendrá un `servicio-cientifico` que no arranca; el README no lo menciona. Solo `levantar.ps1:377-388` genera la llave.
- Importa: el README es el documento de entrada; el flujo documentado falla en el primer intento.
- Corrección: README: indicar `SPRING_PROFILES_ACTIVE=local` en los cinco; añadir `UBICACION_CLAVES=v1:$(openssl rand -base64 32)` y `UBICACION_CLAVE_ACTIVA=v1` al `.env.example` raíz o a los pasos del README.
- Esfuerzo: S.

### OPS-R-3 (Medio) `.env.example` raíz incompleto frente a los `application*.yml`
- Archivo: `.env.example`; consumidores en `*/src/main/resources/application*.yml`.
- Problema: variables que usa el código y que la raíz no declara ni comenta: `UBICACION_*` (6), `CORS_ORIGENES` (api-gateway, application.yml:84; imprescindible al desplegar webs en Vercel), `KEYCLOAK_ADMIN_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_GATEWAY_CLIENT_ID`, `SERVICIO_*_URL` (4, URLs del gateway a los servicios), `GATEWAY_LIMITE_*`, `GATEWAY_CIRCUITO_*`, `GATEWAY_TESELAS_*`, `SERVER_ADDRESS`, y las `<P>_DB_*`/`<P>_MIGR_*`/`*_TIMEOUT_MS` de Aiven (estas sí están documentadas en `.env.example` de cada servicio y en 09 §2, así que es cobertura parcial). Las URLs `SERVICIO_*_URL` y `CORS_ORIGENES` son las que hay que cambiar para cualquier despliegue no local.
- Importa: reproducibilidad y despliegue; hoy hay que leer los yml para saber qué configurar.
- Corrección: añadir al `.env.example` raíz un bloque "opcionales con valor por defecto" (comentado) con estas variables, o un script que compare `${VAR}` de los yml contra los `.env.example` (útil como paso de CI).
- Esfuerzo: S.

### OPS-R-4 (Alto) Dockerfiles existen pero no son multi-stage, no están en el compose y no hay imagen de las webs
- Archivos: `*/Dockerfile` (5), `docker-compose.yml`, `.dockerignore:7-11`.
- Problema: los 5 Dockerfiles son de una sola etapa y copian un jar ya compilado en el host (`COPY servicio-x/target/*.jar`); requieren `mvn package` previo y `.dockerignore` excluye `**/src/`, así que no se pueden construir desde cero (no reproducible, depende de la máquina del que construye). Son mínimos y buenos en lo demás (usuario no root uid 10001, `HEALTHCHECK`, `MaxRAMPercentage`, curl presente en la base temurin). El compose solo levanta infraestructura; ningún servicio ni web corre en contenedor. No hay Dockerfile para `web-interna`/`web-publica` (el build de `dist/` solo existe como script npm).
- Importa: es la ruta natural para "un comando en cualquier SO" y para el backend de la Entrega 3 (OPS-R-6). Ya hay imágenes `biohub/*:latest` locales construidas a mano, pero nada versionado las genera.
- Corrección: Dockerfile multi-stage (`maven:3.9-eclipse-temurin-21` para build con caché `--mount=type=cache`, `eclipse-temurin:21-jre` para runtime) y quitar `**/src/` del `.dockerignore`; servicios y webs (nginx-unprivileged sirviendo `dist/`) en el compose con `profiles: [app]`, `depends_on: condition: service_healthy`. Si no se hace, documentar explícitamente que los Dockerfiles son solo para cargas/pruebas.
- Esfuerzo: L (backend) / M (webs).

### OPS-R-5 (Alto) CI sin lint, sin SAST ni auditoría de dependencias Java, sin build de imágenes
- Archivo: `.github/workflows/ci.yml`.
- Problema: no hay paso de lint en ningún módulo (ni `eslint` en las webs, que no tienen script `lint` en `package.json`, ni checkstyle/spotless en Maven). Solo se audita npm (`npm audit --omit=dev --audit-level=high`); no hay auditoría de dependencias Java (OWASP dependency-check/`mvn versions`/Dependabot: no existe `.github/dependabot.yml`), ni escaneo de imágenes, ni CodeQL. Los Dockerfiles nunca se construyen en CI, así que pueden romperse sin aviso.
- Importa: la rúbrica de DevOps pide lint, auditoría y que falle si algo falla; hoy falla por tests/build/npm audit, pero deja pasar deuda de seguridad en el backend.
- Corrección: `.github/dependabot.yml` (maven, npm, github-actions, docker; S), script `lint` con ESLint en ambas webs y paso en CI (S/M), job `docker build` de los 5 servicios tras `mvn package` (S), y OWASP dependency-check o `trivy fs` con umbral alto (M).
- Esfuerzo: M.

### OPS-R-6 (Alto) La estrategia de despliegue de la Entrega 3 no tiene artefactos ni documento vigente
- Archivos: `docs/equipo/09-despliegue.md:3,5-32,249`, `docs/equipo/00-plan.md:48`, `docs/equipo/04-contrato-api.md:653`.
- Problema: no hay `vercel.json` (ni en `web-publica` ni `web-interna`, SEC ya lo pidió en `08-reporte-seguridad.md:664`), ni workflow de CD, ni Dockerfile/manifest para un host de backend (Render/Railway/Fly), ni decisión escrita de dónde corren gateway, 4 servicios, RabbitMQ, Redis y Keycloak en la nube. 09-despliegue §1 dice textualmente que "la app solo está disponible mientras corras los servicios en tu máquina" y §9 "no hay despliegue de las webs (Vercel)". Aiven (MySQL) sí está operativo (verificado 2026-11-04, §4.5 del doc), pero el resto de la topología objetivo (webs en Vercel + MySQL Aiven + backend X) no existe. Además Keycloak va en `start-dev` con H2, RabbitMQ y Redis son locales; un backend en la nube requeriría hospedarlos (CloudAMQP/Upstash o contenedores) y cambiar `KEYCLOAK_ISSUER_URI`, `CORS_ORIGENES`, `redirectUris` del realm y la URL del gateway que consumen las webs en el build.
- Importa: es el requisito explícito de la Entrega 3 ("sistema funcional"); sin esto solo se puede demostrar en local.
- Corrección: decidir y documentar (una tabla componente→plataforma). Opción más barata y coherente con lo existente: webs en Vercel (`vercel.json` con CSP y rewrite SPA, `VITE_*` como variables de la plataforma), backend completo en una sola VM/Fly.io o Railway con un `docker-compose.prod.yml` (OPS-R-4) más Keycloak con `start` y BD, y MySQL en Aiven. CD manual (`workflow_dispatch`) con secretos en el gestor de la plataforma; no desplegar sin autorización del usuario. Si para la entrega se aceptará demo local, dejarlo escrito como decisión y probar `levantar` en máquina limpia.
- Esfuerzo: L.

### OPS-R-7 (Medio) Documentación desfasada
- Archivos: `README.md:5,58`, `README.md:~323` (sección "10b. Despliegue (implementado)"), `docs/equipo/09-despliegue.md:252-262`.
- Problemas:
  - README:5 sigue diciendo "Estado: esqueleto... No hay lógica de negocio ni modelo de datos todavía" y README:58 "pruebas-carga Reservada para pruebas de carga futuras" (ya hay k6, scripts y compose de carga).
  - 09 §10 dice que `backend` tiene "matriz de 5 entradas" (son 8: se añadió reportes y equipos) y que el E2E de `web-interna` es "condicional" con `hashFiles`; el workflow ya lo ejecuta sin condición (ci.yml:67-74). `servicio-equipos` y `servicio-reportes` aparecen en la matriz pero la tabla no los lista.
  - 09 §1 sigue describiendo "RabbitMQ, Redis, Keycloak" sin mencionar `rabbitmq-usuarios`, y la sección del README sobre "Los servicios... Docker Compose solo levanta la infraestructura (7 contenedores)": son 7 healthy más 1 tarea de una sola vez.
  - README:131-138 "son 7" es correcto solo contando solo los sanos; indicarlo.
- Importa: un evaluador desconfía de un README que dice "esqueleto".
- Corrección: actualizar las frases. Esfuerzo: S.

### OPS-R-8 (Medio) Perfil local vs. aiven: el script y el README no cubren reproducibilidad de Aiven
- Archivos: `levantar.ps1:Perfil`, `09-despliegue.md §4`, `certs/` (ignorado por git, bien).
- Problema: el perfil `aiven` depende de `certs/*.p12`, credenciales por canal privado y de `UBICACION_CLAVES` del equipo; no existe forma de verificar sin esos secretos y las IP deben estar en la lista permitida de Aiven. Con dos servicios de Aiven en cuentas distintas y truststore con ambos CA (§4.5), hay un único punto de fallo humano. Bien documentado, pero sin script de comprobación previa en Linux (`deploy/aiven/verificar-migraciones.sh` solo valida migraciones).
- Importa: para la demo en la nube, sin estos artefactos nadie más puede levantarlo.
- Corrección: tras decidir el despliegue (OPS-R-6), mover credenciales Aiven a los secretos de la plataforma (no a `.env` repartidos) y guardar los `.p12` como secreto binario/base64.
- Esfuerzo: M.

### OPS-R-9 (Medio) Imágenes sin versión fija y sin escaneo; Keycloak solo en `start-dev`
- Archivos: `*/Dockerfile` (`FROM eclipse-temurin:21-jre`), `docker-compose.yml:149,153`.
- Problema: `eclipse-temurin:21-jre` sin digest ni versión menor; `start-dev` con H2 (documentado como no productivo en §9). Resto de imágenes sí con versión (mysql:8.4, redis:7.4-alpine, rabbitmq:4.1, keycloak:26.8.1, curl:8.11.1).
- Importa: builds no reproducibles ante cambios de la base; Keycloak sin persistencia real si se despliega.
- Corrección: fijar `21.0.x-jre` (o digest) y dejar a Dependabot actualizarlo; plan de Keycloak `start` + MySQL para el entorno desplegado.
- Esfuerzo: S (imágenes) / M (Keycloak).

### OPS-R-10 (Bajo) Acciones de CI fijadas por tag, no por SHA; trigger duplicado
- Archivo: `.github/workflows/ci.yml:3-5,25,43`.
- Problema: `on: push` + `pull_request` sin filtrar ramas: cada PR de rama del mismo repo corre dos veces (push y PR), con 13 min en la última. Las acciones (`checkout@v5`, `setup-java@v5`, `upload-artifact@v6`) van por tag móvil. Permisos mínimos y sin secretos: bien.
- Corrección: `push: branches: [main, develop]` + `pull_request`; fijar SHAs (Dependabot los mantiene).
- Esfuerzo: S.

### OPS-R-11 (Bajo) Healthchecks y `depends_on`
- Archivo: `docker-compose.yml`.
- Problema menor: todos los contenedores tienen healthcheck (MySQL por TCP, Redis, RabbitMQ, Keycloak por puerto 9000); `rabbitmq-usuarios` espera a rabbitmq sano. No hay `depends_on` entre Keycloak y MySQL porque usa H2. Sin límites de recursos (`mem_limit`) en ningún servicio.
- Corrección: opcional, límites de memoria para evitar saturar un portátil con 4 MySQL + Keycloak + RabbitMQ.
- Esfuerzo: S.

## Bien hecho

- `docker-compose.yml` ejemplar: puertos solo en `127.0.0.1`, `${VAR:?Falta en .env}` para obligar a configurar secretos, healthchecks en todos los servicios, volúmenes con nombre, root de MySQL restringido (`MYSQL_ROOT_HOST`), contraseñas por entorno y no por argumentos, Redis con `maxmemory`, usuarios AMQP por servicio. `docker compose config --quiet` válido.
- Reglas de repo: `.env` ignorado (`.gitignore`), `*.pem/*.p12/*.key`, `certs/` y `.biohub-local/` ignorados; `.env` no está versionado; `.dockerignore` excluye `.env*`.
- `levantar.ps1` muy completo (validación de herramientas, `.env`, puertos, healthchecks, compilación incremental, siembra, estado, detener) y envoltorio `levantar.cmd` para saltar la política de ejecución.
- Dockerfiles: usuario no root, `HEALTHCHECK`, `ExitOnOutOfMemoryError`, `SERVER_ADDRESS` configurable.
- CI: cubre los 5 módulos backend (matriz con zona UTC y Bogotá, útil), ambas webs (build, vitest, `npm audit`, Playwright en 3 navegadores), job de integración Bruno con Keycloak, RabbitMQ, MySQL y Redis reales; permisos `contents: read`; secretos ficticios aislados; artefactos de fallo; `concurrency` con cancelación; sin secretos reales. Verde en las últimas ejecuciones.
- `.env.example` de raíz y por servicio bien comentados, con advertencias (sin `${}`; sin comentarios al final de línea).
- Documentación operativa de Aiven y rollback honestos (migraciones Flyway no se revierten solas, backups de Aiven).

## Resumen de severidades

| Severidad | IDs |
|---|---|
| Crítico | ninguno |
| Alto | OPS-R-1, OPS-R-2, OPS-R-4, OPS-R-5, OPS-R-6 |
| Medio | OPS-R-3, OPS-R-7, OPS-R-8, OPS-R-9 |
| Bajo | OPS-R-10, OPS-R-11 |
