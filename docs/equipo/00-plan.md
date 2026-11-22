# 00 – Plan: esqueleto BioHub (Ingeniería de Software 2)

## Objetivo
Dejar el monorepo de BioHub listo para que el equipo implemente historias de usuario:
estructura, infraestructura local (Docker), seguridad base (Keycloak + JWT) y una prueba de punta a punta (`/ping`).
**Sin lógica de negocio ni modelo de datos.**

## Arquitectura (decidida, no se cambia)
Arquitectura basada en servicios + API Gateway propio (Spring Boot, sin Spring Cloud Gateway) + eventos asíncronos en topología broker (RabbitMQ).

## Stack
- Backend: Java 21, Spring Boot 3.5.16, Maven (pom agregador en la raíz).
- Frontend: React 19 + Vite 8, keycloak-js 26 (web-interna).
- Infra: MySQL 8.4 (4 instancias), Redis 7.4, RabbitMQ 4 (management), Keycloak 26.8.1 (start-dev).

## Puertos
| Componente | Puerto |
|---|---|
| api-gateway | 8080 |
| servicio-cientifico | 8081 |
| servicio-equipos | 8082 |
| servicio-consulta-publica | 8083 |
| servicio-reportes | 8084 |
| web-interna / web-publica | 5173 / 5174 |
| keycloak | 8180 |
| mysql transaccional / lectura / analitica / gateway | 3307 / 3308 / 3309 / 3310 |
| redis | 6379 |
| rabbitmq / management | 5672 / 15672 |

## Tablero
| # | Tarea | Responsable | Estado |
|---|---|---|---|
| 0 | Verificar herramientas (Git, Java 21, Maven, Node 22, Docker) | Tech Lead | Hecho |
| 1 | Monorepo + pom agregador + paquetes | Tech Lead | Hecho |
| 2 | Dependencias por servicio | Tech Lead | Hecho |
| 3 | docker-compose + .env + init MySQL | Tech Lead | Hecho |
| 4 | Realm Keycloak | Tech Lead | Hecho |
| 5 | Migraciones Flyway V1 | Tech Lead | Hecho |
| 6 | Ping de punta a punta (servicios, gateway, webs) | Tech Lead | Hecho |
| 7a | .gitignore, .editorconfig | Tech Lead | Hecho |
| 7b | README y documentación | documentador-tecnico | Hecho |
| 8 | Verificación (build, compose, pings con token, E2E navegador) | Tech Lead | Hecho |
| 8b | Validación de la prueba de punta a punta | qa-tester | Hecho – Aprobado con observaciones |
| 8c | Revisión de seguridad de la configuración | ciberseguridad | Hecho – Aprobado con observaciones |
| 8d | Corrección de hallazgos QA/seguridad + re-verificación desde cero | Tech Lead | Hecho |
| 9 | Git: main, develop, ramas feature, push | Tech Lead | Hecho |
| 10 | Protección de main/develop: solo por pull request, sin aprobación obligatoria, sin force push ni borrado | Tech Lead | Hecho |
| 11 | Decisión de despliegue: webs en Vercel; backend on-premise en 2 VMs (D11) | Equipo | Reemplazada en la iteración 15 (MySQL en Aiven) |
| 12 | Reparto de ramas por persona en el README (Juanita: ubicación/consulta pública; Santiago: seguridad/acceso + infra compartida; Hernando: registro; Nicolás: reportes/equipos) | Tech Lead | Hecho |
| 13 | Preparar despliegue (Dockerfiles, compose prod, Keycloak prod) | devops | Reemplazada por la iteración 15 |

## Agentes no usados y por qué
product-owner, ux-ui-designer, dba, arquitecto-software, devops (compose lo hizo el Tech Lead; CI/CD y dockerizar servicios quedan pendientes): no hay requisitos ni modelo de datos todavía y la arquitectura ya está decidida.
Entrarán con cada historia de usuario.

## Resultados de verificación (Tech Lead)
- `mvn package`: OK, 31 tests tras las correcciones (gateway 23, cada servicio 2).
- web-interna / web-publica: `npm install`, `npm test` (2+2), `npm run build` OK; `npm audit --omit=dev`: 0 vulnerabilidades.
- `docker compose up -d`: 7/7 contenedores healthy. Realm importado.
- Pings por gateway con token de cada usuario de prueba: 200. Sin token: 401; token falso / de otro realm: 401; ruta inexistente: 404; servicio caído: 502; CORS de otro origen: 403.
- E2E navegador (Playwright): login PKCE con investigador1 → ping del Servicio Científico mostrado; token no persistido en storage.

## Pendientes (asignados a historias)
- SEC-02 reglas por rol y `denyAll` por defecto · SEC-04 usuarios RabbitMQ por servicio · SEC-05 API Keys + rate limit · SEC-11 usuario Flyway separado · SEC-12 variables por servicio en despliegue.
- CI/CD (GitHub Actions) y Dockerfiles de los servicios (devops) – no pedidos en el esqueleto; ver tarea 13 y D11.

---
# Iteraciones 13 y 14 (retiradas)

Despliegue de bases, Redis y RabbitMQ en VMs locales con una VPN de malla, y su endurecimiento. **Retirado en la iteración 15**: la VPN se eliminó del proyecto y MySQL pasa a Aiven. Se conserva de esas iteraciones:
- PR #2 fusionado a `develop` (5a8d206): eventos RabbitMQ de punta a punta, usuarios RabbitMQ por servicio; `mvn -B verify` 75 tests, 0 fallos.
- Contrato del evento `hallazgo.registrado` (registro/Hernando publica, Juanita y Nicolás consumen) en `04-contrato-api.md`.
- Cada integrante conserva sus responsabilidades: no se toca código de registro, equipos/reportes ni seguridad/acceso.

---
# Iteración 15: retiro de la VPN de malla, MySQL en Aiven, CI y cierre de la parte de Juanita

**Fecha:** 2026-11-03 · **Rama:** `feature/ubicacion-consulta-publica` (en `~/biohub`; se retiró el worktree `~/biohub-ubicacion`) · **Modo de presupuesto:** equilibrado, sin tope.

**Objetivo:** quitar la VPN de malla de todo el repo; conectar los servicios de Juanita a MySQL en Aiven (SSL) con perfil `local` (Docker) y perfil `aiven`; Flyway trazable; GitHub Actions (build web-publica, JUnit, Bruno); completar y verificar la parte de Juanita contra SRS/SAD.

**Alcance:** solo la parte de Juanita: `servicio-cientifico` módulo `ubicacion`, `servicio-consulta-publica`, `web-publica`. Otros servicios solo se tocan lo mínimo para que el de Juanita funcione, y se marca en la matriz. la VPN de malla anterior se retira de todo el repo (autorizado por Juanita).

**Fuentes de verdad:** `docs/entregas/` (SRS, SAD y C4 en PDF + texto extraído; no versionado, excluido en `.git/info/exclude`).

**Decisiones**
- D-I15-1: se mantiene la convención de Flyway por fecha (`V1__base.sql`, `VAAAAMMDD_HHMM__...`, `out-of-order`) porque el esquema `cientifico` lo comparten dos integrantes. Bases de Aiven nuevas: sin baseline.
- D-I15-2: Aiven con un servicio MySQL y una base + usuario por servicio; URL configurable para poder separar escritura y lectura en servicios distintos (riesgo ASR2/ASR4, lo documenta arquitectura).
- D-I15-3: RabbitMQ, Redis y Keycloak siguen en Docker local; en CI, como contenedores.
- D-I15-4: commits solo a nombre de Juanita, sin Co-Authored-By; sin push/merge.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 15a | Reconocimiento, documentos a `docs/entregas/`, línea base de tests | Tech Lead | opus/medium | Hecho |
| 15b | Matriz de trazabilidad `trazabilidad.md` | product-owner | sonnet/medium | Hecho (13 de Juanita: 4 Cumple, 9 Parcial; 14 ambigüedades A1–A14) |
| 15c | Verificación del código contra el SAD; impacto de Aiven/Flyway en ASR; quitar la VPN de malla de `03-arquitectura.md` | arquitecto-software | opus/high | Hecho (504a36f; 25 hallazgos: de Juanita 2 Altos ARQ-05, ARQ-06 y 11 Medios/Bajos; ARQ-12 y ARQ-15 corregidos en 8bdac84) |
| 15d | Quitar la VPN de malla (resto del repo); perfiles local/aiven con SSL; `.env.example` | devops | sonnet/medium | Hecho (fe1bb43, 52ac256; perfil aiven probado con SSL contra MySQL local, falta Aiven real) |
| 15e | Migraciones compatibles con Aiven; convención en README de cada servicio | dba | sonnet/high | Hecho (6d64b9e, 62f304c; 6 migraciones compatibles, sin baseline) |
| 15f | GitHub Actions + colecciones Bruno | devops + qa-tester | sonnet/medium | Hecho (Bruno 44/44 requests, 77/77 tests en local; actionlint OK; job en GitHub sin ejecutar aún) |
| 15g | Completar la parte de Juanita (SEC-J-01..03 y faltantes) | backend-senior / frontend-senior | sonnet/high · sonnet/medium | Hecho (d92c920, 31fd86b, 98e0021, 0e959d2; 2 ciclos de corrección: ARQ-26 Alto + SEC-J-10/11/14/15/16, BUG-J2/J3) |
| 15h | Verificación final: build, tests, k6, auditoría, ASR en código | qa-tester · ciberseguridad · arquitecto | sonnet/medium · opus/high · opus/high | Hecho (QA APROBADO; seguridad APROBADO CON OBSERVACIONES, 0 Crít/Alt; ASR1 y ASR4 Parciales por D42; cientifico 241/0/2 omitidos opt-in, consulta-publica 190/0) |

## Modelos y consumo
| Fase | USD acumulado | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 15a Reconocimiento | 0.80 | sesión principal (opus) | — |
| 15b–15e Diseño, retiro de la VPN, Aiven, Flyway | 9.36 | arquitecto-software (opus, 3.45) | — (todos según matriz equilibrado) |
| 15f–15g (sesión 2026-11-03, sin medir aparte) | — | — | — |
| 15h verificación + 2 ciclos (sesión 2026-11-04) | 10.89 en esta sesión (≥ 20.25 acumulado; 15f–15g no se midió) | ciberseguridad (opus, 4.08 en 2 tareas) | backend ciclo 2 sonnet/**medium** (matriz: high): solo reglas ArchUnit ya diseñadas por seguridad |
| 15h ciclo 1 re-verificación | (incluido en la fila anterior) | — | ciberseguridad opus/**medium** (matriz: high): solo re-verifica un diff acotado de su propia auditoría |
| 15h CI en GitHub + arreglo WebKit/Bruno (sesión 2026-11-04) | 13.80 en esta sesión (≥ 23.16 acumulado) | ciberseguridad (opus, 4.08) | — |
| Cierre 2026-11-04: PR #3–#5 a develop, Aiven escritura y lectura reales, credenciales al equipo | 20.02 en esta sesión (≥ 29.38 acumulado) | sesión principal (opus, 10.77) | devops sonnet/medium se cortó por límite de uso; el Tech Lead terminó la limpieza y el aprovisionamiento |

## Pendientes tras la 15h
- Corridas k6 que exige D42: degradación activa, 1 vs 2 réplicas en saturación (ARQ-30, ARQ-31) y par ASR4 `CIENTIFICA=0/1` (ARQ-32).
- SEC-J-12, SEC-J-13 (guarda de coordenadas en el publicador, sube de prioridad), SEC-J-04/05/07/08(3)/09, SEC-04/SEC-D-03 (un usuario AMQP por servicio, Santiago).
- ARQ-05 (segundo servicio Aiven sin evidencia), ARQ-07 (Hernando), ARQ-28/29/33, Aiven real sin probar. CI en GitHub: verde en run 41891195794 (740bb37), los 4 jobs.
- Condición D44: nada de registro (Hernando) entra sin pasar las reglas ArchUnit actuales.
- Hecho por la usuaria: DLQ purgada, `.env` actualizado, contraseñas de `avnadmin` rotadas, credenciales y llave de cifrado en gestor de contraseñas, zips enviados por privado.
- Usuaria: agregar en Aiven (Allowed IP addresses) las IP que envíen Hernando, Nicolás y Santiago.
- Corridas k6 (D42) pospuestas por decisión de la usuaria hasta que los servicios de todos estén completos; repetir todo sobre `develop` antes de la entrega.
- Cuando `develop` esté estable: PR `develop` → `main`.

# Iteración 16: pendientes propios de Juanita (seguridad y arquitectura)

**Fecha:** 2026-11-04 · **Rama:** `feature/ubicacion-consulta-publica` · **Modo de presupuesto:** equilibrado, sin tope.

**Alcance:** solo hallazgos cuyo responsable es Juanita. Fuera de alcance: SEC-J-05, SEC-J-07 (web-interna), SEC-J-09, SEC-04/SEC-D-03 (Santiago); ARQ-07 (Hernando); k6 de D42 (pospuesto por la usuaria); el proxy de teselas de SEC-J-04 (Santiago). Decisiones nuevas: D53 (ARQ-29) y D54 (SEC-J-13) en `03-arquitectura.md`.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 16a | ARQ-33: comentarios de `application-aiven.yml` (cientifico y consulta-publica) | Tech Lead | opus/medium | Hecho |
| 16b | SEC-J-13, SEC-J-12, SEC-J-08.3, ARQ-28, ARQ-29 en `servicio-cientifico` | backend-senior | sonnet/high | Hecho (786f4e1, 57ab7ad, 1e449ac, 25d2175, aeb67d8; queda ARQ-28b aceptado) |
| 16c | SEC-J-04 (zoom con un punto y `referrerPolicy`) en `web-interna/src/ubicacion` | frontend-senior | sonnet/medium | Hecho (47b5cc9) |
| 16d | Verificación: tests y re-auditoría del diff | qa-tester · ciberseguridad | sonnet/medium · opus/medium | Hecho: QA APROBADO (27a6dd9, BUG-J4 tests herméticos frente al `.env`; cientifico 264/0/2 omitidos, consulta-publica 194/0, web-interna 46/46, web-publica 32/32). Seguridad APROBADO CON OBSERVACIONES (d249733): SEC-J-12 Cerrado; SEC-J-04, 08 y 13 Parciales; nuevos SEC-J-17 y SEC-J-18 (Bajos) |

**Pendientes tras la iteración 16 (de Juanita):** SEC-J-17 (guarda de coordenadas eludible; condición previa para publicar `hallazgo.*` reales), SEC-J-18 (auditoría falla abierta), SEC-J-I14 (verificar teselas OSM con `no-referrer` en el navegador), ARQ-28b. Usuaria: borrar las VMs retiradas `biohub-backend` y `biohub-datos` (el borrado desde la sesión fue bloqueado por permisos).

**Modelos y consumo (iteración 16):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 16a–16d (sesión 2026-11-04 tarde) | 4.91 en esta sesión (≥ 34.29 acumulado) | backend-senior (sonnet, 1.79) | ciberseguridad opus/**medium** (matriz: high): re-verificación de un diff acotado, como en 15h |

---
# Iteración 16: seguridad y acceso (parte de Santiago)

**Fecha:** 2026-11-05 · **Rama:** `feature/seguridad-acceso`.

**Alcance:** `api-gateway`, Keycloak y roles, infraestructura compartida. Las pantallas de `web-interna` van al final, cuando existan los endpoints de negocio. Guía para el equipo: `11-seguridad-y-acceso.md`. Decisiones: D45 a D51.

| # | Tarea | Estado |
|---|---|---|
| 16a | Reglas por rol y `denyAll` en el gateway (SEC-02) | Hecho |
| 16b | Realm: bloqueo a 3 intentos, cliente `biohub-gateway`, usuario `sinrol1` | Hecho |
| 16c | Migración, consumidores y API Keys solo con hash (HU-12) | Hecho |
| 16d | Límite en Redis con respaldo en memoria (RNF11, ASR8) | Hecho |
| 16e | Filtro de API Key y límite por IP con falla cerrada (SEC-05) | Hecho |
| 16f | API externa `/api/v1/externa/fichas/**` (ASR9) | Hecho |
| 16g | Usuarios contra Keycloak y revocación inmediata (HU-10, RF19) | Hecho |
| 16h | Usuarios RabbitMQ por servicio (SEC-04) y `maxmemory` en Redis | Hecho; falta que cada servicio migre su `.env` |
| 16i | Gateway en CI y carpeta Bruno `gateway/` | Hecho; en verde en GitHub desde el PR #7 (`develop`, run 42533445668) |
| 16j | Router, menú por rol, montaje de `src/ubicacion/`, pantallas de API Keys y usuarios | Pendiente, al final |
| 16k | Circuit breaker en el gateway (ARQ-20, D52) | Hecho |
| 16l | Acciones de GitHub en versiones con Node 24 (`checkout@v5`, `setup-java@v5`, `setup-node@v5`, `upload-artifact@v6`) | Hecho; verificado en GitHub (PR #7) |
| 16m | Proxy de teselas del mapa en el gateway (SEC-J-04, D56) | Hecho en el gateway; falta que `MapaHallazgos.jsx` lo use (Juanita, `11-seguridad-y-acceso.md` §7) |
| 16n | Prueba de carga k6 de ASR8: abuso, cumplidores y revocación (`pruebas-carga/k6/gateway-limite-api-externa.js`) | Hecho; 2 corridas en verde (`10-verificacion-sad.md`) |

**Verificación (2026-11-14, 16m y 16n):**
- `mvn -pl api-gateway test`: 200 pruebas, 0 fallos, 0 omitidas (32 nuevas de teselas).
- Entorno desechable, con credenciales ficticias y sin Aiven:
  - Tesela real de OSM a través del gateway: 401 sin token, 403 sin rol, 400 con zoom 13 y 200 `image/png`. La segunda petición sale de la caché (12 ms frente a 816 ms).
  - En el log hay 0 menciones de las coordenadas de la tesela y 0 API Keys completas.
- k6 de ASR8 con A a 20 y a 200 req/s: todos los umbrales se cumplen (`resultados/asr8-gateway.json`, `asr8-gateway-abuso200.json`).


**Verificación (2026-11-05):**
- `mvn -pl api-gateway test`: 156 pruebas, 0 fallos, 0 omitidas (168 tras 16k) (Testcontainers con MySQL con reglas de Aiven, Redis y Keycloak con el realm real).
- Colección Bruno completa en un entorno desechable, con el JDK y Node locales: 73 de 73 peticiones, con `servicio-cientifico` conectado como `cientifico_mq` y `servicio-consulta-publica` como `consulta_mq`.
- Pruebas de mutación manuales: con multipart activado y con `failureFactor: 5`, las pruebas correspondientes fallan.
- Sin ejecutar: la migración en Aiven real y los servicios de Nicolás con su usuario `reportes_mq`.

**Hallazgos durante la verificación:** el proxy dejaba vacíos los adjuntos multipart (D51) y SEC-D-03 estaba equivocado sobre `biohub.eventos.dlx` (D50).

**CI tras el PR #6 (2026-11-08):** `develop` quedó en rojo por dos pruebas inestables de otra parte, sin relación con el gateway: `ValidadorDatosHallazgoTest.contenidoEstrictoFechaYCaracteresDeControl` (consulta-publica) calcula "mañana" con la zona del servidor y el validador con la de Bogotá, así que falla entre las 00:00 y las 05:00 UTC; y `RecifradoServiceTest.lasFilasIlegiblesQuedanPendientesYSeCuentanComoFallidas` (científico) falló una vez y pasó en las corridas siguientes. Dueña: Juanita.

---
# Iteración 17: integrar el PR #7 y aplicar `11-seguridad-y-acceso.md` (parte de Juanita)

**Fecha:** 2026-11-08 · **Rama:** `feature/ubicacion-consulta-publica` · **Modo de presupuesto:** equilibrado, sin tope.

**Alcance:** Aiven (lista de IP y conectividad), merge de `develop` con el PR #7, secret del gateway y reimportación de Keycloak, `denyAll` + `@PreAuthorize` + pruebas 401/403 en `ubicacion` (servicio-cientifico) y `servicio-consulta-publica`, CI rojo (`ValidadorDatosHallazgoTest` por zona horaria, `RecifradoServiceTest` inestable). Reglas: gateway solo con perfil `local`, nada de push sin confirmar, commits sin Co-Authored-By.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 17a | IP en Aiven escritura (propia y de compañeros); conectividad escritura y lectura (TLS `VERIFY_IDENTITY`) | Usuaria · Tech Lead | opus/medium | Hecho |
| 17b | Merge de `develop` (da4152a); D45/D46 de ubicación renumeradas a D53/D54 (7e4a52f) | Tech Lead | opus/medium | Hecho |
| 17c | `KEYCLOAK_GATEWAY_CLIENT_SECRET` en `.env` raíz; reimportar Keycloak; verificar `sinrol1`, bloqueo a 3 intentos y client del gateway | Tech Lead | opus/medium | Hecho |
| 17d | `denyAll`, `@PreAuthorize` en cada endpoint y pruebas 401/403/2xx (cientifico-ubicacion y consulta-publica) | backend-senior | sonnet/high | Hecho (d0be1be, a0e0d32; D55) |
| 17e | CI: zona horaria de `ValidadorDatosHallazgoTest` y causa raíz de `RecifradoServiceTest` | backend-senior | opus/high | Hecho (b4053c3, d9a9031, y el resto de pruebas con 0xFF) |
| 17f | E2E por el gateway (perfil local): 2xx / 403 con `sinrol1` / 401 sin token; API Keys y límites | qa-tester | sonnet/medium | Hecho: APROBADO (f695c8a). cientifico 322/0, consulta-publica 239/0, gateway 168/0 en TZ UTC y Bogotá; E2E por gateway local 50/50 |
| 17g | Auditoría: denyAll, `@PreAuthorize`, secretos en la rama, arreglo del recifrado | ciberseguridad | opus/high | Hecho: APROBADO CON OBSERVACIONES (45ce862). D55 aceptada con condiciones; SEC-J-19/20/21 Bajos; sin secretos en árbol ni historial |

**Modelos y consumo (iteración 17):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 17a–17c (Aiven, merge, Keycloak) | 2.27 en esta sesión | sesión principal (opus) | — |
| 17d–17g (denyAll, CI, QA, seguridad) | 8.95 en esta sesión | sesión principal (opus, 3.98); backend-senior 17e (opus, 2.12) | backend-senior 17e en **opus**/high (matriz: sonnet/high): causa raíz de una prueba inestable que toca cifrado |

**Pendientes tras la iteración 17 (de Juanita):**
- SEC-J-19 (endurecer `ArquitecturaSeguridadTest`) y SEC-J-20 (403 problem+json fuera de `ubicacion`): condición de D55 antes de que `registro` integre controladores. OBS-J17-1 (403/401 vacío en `/api/publica/eventos/recibidos`) se resuelve con el mismo handler.
- Confirmar con el grupo las 4 rutas fuera de la matriz §2: `GET /api/cientifico/ping`, `POST /api/cientifico/eventos/prueba`, `GET /api/publica/ping`, `GET /api/publica/eventos/recibidos`; y D55.
- §3 de `11`: confirmar que `/api/publica/fichas` y `/fichas/{id}` no cambian; si 120/min por IP alcanza; aviso de 429 con `Retry-After` en `web-publica`.
- SEC-04: migrar `servicio-cientifico/.env` y `servicio-consulta-publica/.env` a `cientifico_mq`/`consulta_mq`.
- Aiven: escritura y lectura ya tienen las IP del equipo con /32 (según la usuaria, 2026-11-08); falta acordar con el grupo quitar `0.0.0.0/0` y `::/0`.
- Propuesta: una entrada del CI con `TZ: America/Bogota`.


---
# Iteración 18: cierre de SEC-J-19/20/22, verificación y preparación del PR a develop

**Fecha:** 2026-11-09 · **Rama:** `feature/ubicacion-consulta-publica` · **Modo de presupuesto:** equilibrado, sin tope.

**Alcance:** terminar lo pendiente de la iteración 17 (quitar `Co-Authored-By` de los 2 commits locales, guardar `docs/revision/`, verificar SEC-J-19 y SEC-J-20), corregir lo que salió de esa verificación (SEC-J-22, BUG-J18-1) y dejar la rama lista para push y PR a `develop`. Revisión del avance de `origin/feature/registro-cientifico` (Hernando), sin tocar su código.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 18a | Reconocimiento: pendientes de ayer, pruebas de la rama, integración de prueba con `registro` | Tech Lead | opus/medium | Hecho: científico 437/0 (2 omitidas), consulta-pública 339/0. `registro` no compila con esta rama: importa `ubicacion.service.EstadoConservacion` (D53); su CI rojo es la falla de TZ ya arreglada aquí |
| 18b | Quitar `Co-Authored-By` (fc769a9, efed795) y commit de `docs/revision/` (4b9b5dc) | Usuaria · Tech Lead | — | Hecho (la reescritura la ejecutó la usuaria; el clasificador bloqueó al Tech Lead) |
| 18c | QA de SEC-J-19/20 | qa-tester | sonnet/medium | Hecho: APROBADO (5483a1b). OBS-J17-1 cerrado. BUG-J18-1 (Bajo, prueba intermitente) |
| 18d | Seguridad de SEC-J-19/20 | ciberseguridad | opus/medium | Hecho: APROBADO CON OBSERVACIONES (6827c17). SEC-J-20 cerrado; SEC-J-22 (Bajo) nuevo; SEC-J-23 (Medio, Hernando) |
| 18e | SEC-J-22 y BUG-J18-1 (solo `src/test`) | backend-senior | sonnet/high | Hecho (99fdf15, 89e280e). verify UTC y Bogotá: científico 458/0 (2 omitidas), consulta-pública 364/0; BUG-J18-1 15/15 |
| 18f | Re-verificación de SEC-J-22 | ciberseguridad | opus/medium | Hecho: APROBADO (d837520). SEC-J-19 y SEC-J-22 cerrados; SEC-J-I23 informativo (XS); condición de D55 cumplida en esta rama |
| 18g | Push de la rama, CI y PR a `develop` | Tech Lead | opus/medium | Hecho: PR #8 abierto el 2026-11-09 (CI verde); se completa en 19a |

**Pendientes tras la iteración 18:**
- Hernando (mensaje enviado por la usuaria): dejar de importar `ubicacion` en `registro` (D53), SEC-J-23 (roles de `administrador` fuera de la matriz §2), agregar sus endpoints a `SeguridadEndpointsTest.ENDPOINTS`, conflicto en `04-contrato-api.md`.
- De Juanita: SEC-J-I23 (XS), SEC-04 (`.env` con `cientifico_mq`/`consulta_mq`), corridas k6 de D42 cuando estén todos los servicios.
- Santiago: 16j (pantallas de `web-interna`), SEC-J-I19.

**Modelos y consumo (iteración 18):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 18a–18f (sesión 2026-11-09) | 5.60 en esta sesión (opus 4.31, sonnet 1.29) | sesión principal (opus, 2.54); ciberseguridad 1.77 en 2 tareas | ciberseguridad opus/**medium** (matriz: high) en 18d y 18f: re-verificación de un diff acotado de su propia auditoría, como en 15h y 16d |

---
# Iteración 19: integración con develop

**Fecha:** 2026-11-15 · **Rama:** `feature/ubicacion-consulta-publica` · **Modo de presupuesto:** equilibrado, sin tope.

**Alcance:** traer `develop` (PR #9: proxy de teselas del gateway, SEC-J-04/D56, evidencia k6 de ASR8) a la rama sin perder el avance propio, y mezclar el PR #8 en `develop`.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 19a | Mezcla de prueba con `origin/develop` en una copia aparte: sin conflictos (solo 5 documentos de `docs/equipo/` en común), sin decisiones duplicadas; `verify` con TZ=UTC: api-gateway 200/0, científico 458/0 (2 omitidas), consulta-pública 364/0 | Tech Lead | opus/medium | Hecho |
| 19b | Borrar la copia de trabajo y la rama sobrantes `worktree-agent-a81b33dc5352a050a` (sus 2 commits ya estaban en la rama como b4053c3 y d9a9031) | Tech Lead | — | Hecho |
| 19c | Merge de `origin/develop` en la rama (9ed29ec), push, CI y merge del PR #8 en `develop` | Tech Lead | opus/medium | Hecho: CI del PR verde en b171bf0 (5/5 trabajos); PR #8 mezclado en `develop` (060f5e5, merge sin squash); la rama sigue abierta |

**Pendientes tras la iteración 19:**
- De Juanita: `MapaHallazgos.jsx` debe pedir las teselas a `/api/gateway/teselas/{z}/{x}/{y}.png` con el JWT (`11-seguridad-y-acceso.md` §7; parte front de SEC-J-04). SEC-04, SEC-J-I23, entrada del CI con `TZ: America/Bogota`, k6 de D42.
- Hernando: D53 cumplido en `origin/feature/registro-cientifico` (f8ae73a, ya no importa `ubicacion`). Siguen SEC-J-23 y sus endpoints en `SeguridadEndpointsTest.ENDPOINTS`.
- Santiago: 16j, SEC-J-I19.

**Modelos y consumo (iteración 19):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 19a–19c (sesión 2026-11-15) | 0.69 en esta sesión (opus 0.69) | sesión principal (opus, 0.69) | — (sin subagentes: integración y pruebas las corrió el Tech Lead) |

---
# Iteración 20: teselas del mapa por el gateway (parte front de SEC-J-04)

**Fecha:** 2026-11-15 · **Rama:** `feature/ubicacion-consulta-publica` · **Modo de presupuesto:** equilibrado, sin tope.

**Alcance:** `web-interna/src/ubicacion/componentes/MapaHallazgos.jsx` pide las teselas a `/api/gateway/teselas/{z}/{x}/{y}.png` con el JWT a través del `cliente` inyectado (D17), en vez de a `tile.openstreetmap.org`. Dueña: Juanita (D17; `11-seguridad-y-acceso.md` §7). El proxy del gateway es de Santiago y ya está en `develop` (PR #9). Santiago no tiene que cambiar nada: sigue aportando solo `apiUrl` y `obtenerToken`.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 20a | Capa de teselas con `fetch` + JWT (aborto por tesela, revocación de object URL, 401 → `alSesionVencida`), `getBlob` en el cliente, pruebas Vitest | frontend-senior | sonnet/**high** | Hecho (sin commit): Vitest 73/73 (verificado por el Tech Lead), build OK; sin referencias a `tile.openstreetmap.org`; sin dependencias nuevas |
| 20b | QA de 20a | qa-tester | sonnet/medium | Hecho: APROBADO CON OBSERVACIONES. Vitest 84/84 con `qa-teselas.test.jsx` (11 casos borde), build OK. BUG-J20-1 (Bajo, `alSesionVencida` una vez por capa y no por mapa); BUG-J20-2 (Bajo, doc) corregido por el Tech Lead en WI-05; WI-15 (navegador real) pendiente |
| 20c | Seguridad de 20a (token, privacidad de z/x/y, fugas) | ciberseguridad | opus/high | Hecho: APROBADO CON OBSERVACIONES. 0 Críticos/Altos/Medios; SEC-J-24 (Bajo, teselas 24 h en caché de disco: decisión pendiente de la usuaria); SEC-J-I24 (CSP necesita `data:`) corregido por el Tech Lead en README, `11` y `08`; SEC-J-I25 a I27 informativos. SEC-J-I14 cerrado; SEC-J-04 se cierra con la prueba manual en navegador |
| 20d | Commit, push y PR a `develop` | Tech Lead | opus/medium | Hecho: PR #10 mezclado en `develop` (7143a6b, merge sin squash, CI verde en 09afeb8); la rama sigue abierta |

**Cambios de modelo:** frontend-senior sonnet/**high** (matriz: sonnet/medium) en 20a: maneja el token y la privacidad de las coordenadas.

**Pendientes tras la iteración 20:**
- ~~Usuaria: decidir SEC-J-24~~ → decidido: zoom ≥ 9 sin caché en el front (D57, tareas 20e–20g).
- De Juanita (Bajos/informativos): BUG-J20-1, SEC-J-I25 (validar tipo del blob; `...opciones` antes de `headers`), SEC-J-I26 (`referrerPolicy: 'no-referrer'` en `pedir`; nota de proxies en `09`), SEC-J-I27 (fijar `leaflet` 1.9.4, coordinar con Santiago porque `package.json` es suyo). Prueba manual WI-15 en navegador para cerrar SEC-J-04.
- Santiago: SEC-J-25 (recargar en `pageshow` con `persisted` tras cerrar sesión); opcional `Clear-Site-Data: "cache"` al cerrar sesión; montar `src/ubicacion/` en `App.jsx` (16j); CSP de SEC-J-07 con `img-src 'self' data: blob:`; crear el `cliente` una sola vez; si inyecta un cliente propio, implementar `getBlob`.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 20e | SEC-J-24: `ZOOM_SIN_CACHE = 9`, `getBlob(..., { sinCache })` con `no-store`; `...opciones` antes de `headers` (SEC-J-I25); D57; pruebas | Tech Lead | opus/medium | Hecho (sin commit): Vitest 86/86, build OK; mutación `>=`→`>` detectada por la prueba |
| 20f | QA de 20e | qa-tester | sonnet/medium | Hecho: APROBADO CON OBSERVACIONES, sin bugs nuevos. Vitest 91/91 (5 casos más en `qa-teselas.test.jsx`), build OK. Nota: BUG-J20-2 ya estaba corregido (WI-05) |
| 20g | Re-verificación de SEC-J-24 | ciberseguridad | opus/**medium** | Hecho: APROBADO CON OBSERVACIONES. SEC-J-24 cerrado (falta verlo en navegador junto con WI-15); SEC-J-I25 parcial (orden de `...opciones` cerrado; queda `blob.type` y png/jpeg en el gateway); SEC-J-I28 (doc) corregido por el Tech Lead antes del commit; SEC-J-25 nuevo (Bajo, bfcache tras cerrar sesión, de Santiago) |

**Cambios de modelo:** 20e la hizo el Tech Lead (cambio de ~40 líneas; no justificaba lanzar un agente). Ciberseguridad opus/**medium** (matriz: high) en 20g: re-verificación de un diff acotado de su propio hallazgo, como en 18d y 18f.

**Modelos y consumo (iteración 20):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 20a–20d (sesión 2026-11-15) | 2.96 acumulado en la sesión (≈2.27 en la iteración 20; opus 2.44, sonnet 0.52) | sesión principal (opus, 1.62); ciberseguridad 0.82; frontend-senior 0.34; qa-tester 0.18 | frontend-senior sonnet/**high** (matriz: medium): token y privacidad de coordenadas |
| 20e–20g (SEC-J-24, D57) | 4.75 acumulado en la sesión (≈1.79 en 20e–20g; opus 4.11, sonnet 0.64) | sesión principal (opus) | ciberseguridad opus/medium en 20g (re-verificación acotada) |

---
# Iteración 21: arnés de desarrollo con E2E de ubicación, y CI de web-interna

**Fecha:** 2026-11-15 · **Rama:** `feature/ubicacion-consulta-publica` · **Modo de presupuesto:** **calidad** (pedido por la usuaria para esta iteración), sin tope.

**Alcance:** (1) arnés solo de desarrollo para abrir y probar las pantallas de `src/ubicacion/` en navegador real sin esperar a 16j, con E2E de Playwright (incluido WI-15 para cerrar SEC-J-04) y guía manual en modo real (D58); (2) job de `web-interna` en la CI y entradas de backend con `TZ: America/Bogota`.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 21a | Arnés `src/ubicacion/dev/`, Playwright E2E, guía manual (D58) | frontend-senior | opus/medium | Hecho (sin commit): Playwright 117/117 (39 por motor, imagen oficial con CI=1); verificado por el Tech Lead: Vitest 92/92, `dist/` sin arnés, Chromium 39/39. Las E2E destaparon 2 defectos en `MapaHallazgos.jsx` (Enter no abría el detalle; error `_leaflet_pos` al pasar a Lista durante el zoom), corregidos |
| 21b | Job `web-interna` en CI (E2E condicional) y matriz con `America/Bogota` | devops | sonnet/high | Hecho (sin commit): 8 jobs (web-interna + backend 5 entradas: científico y consulta-pública en UTC y Bogotá, gateway en UTC); `-DargLine=-Duser.timezone`; YAML y actionlint OK; local web-interna 91/91, audit 0; `ValidadorDatosHallazgoTest` con Bogotá 20/0. Sin checks requeridos en `develop`/`main`, así que el cambio de nombre de los jobs no bloquea |
| 21c | QA de 21a y 21b | qa-tester | sonnet/high | Hecho: APROBADO CON OBSERVACIONES. Vitest 92/92; E2E 78/78 ×3 (Chromium y Firefox) y 117/117 ×2 en la imagen oficial, sin inestabilidad; 10 mutaciones (7 detectadas por E2E); consulta-pública 364/0 en UTC y Bogotá. BUG-J21-1/2/3 (Bajos, solo pruebas). `06` P14 con el mapa WI-* → specs |
| 21d | Seguridad: el arnés y el modo simulado no llegan a producción; dependencia nueva `@playwright/test` | ciberseguridad | opus/high | Hecho: APROBADO CON OBSERVACIONES, solo 5 informativos (SEC-J-I29 a I33); el arnés es seguro tal como está (build normal y forzado sin modo simulado; token falso rechazado por el gateway; ningún despliegue sirve `vite dev`). I29 (E2E sin condición en la CI), I30 (nota de trazas) e I33 (webs solo desde `dist/`) aplicados por el Tech Lead antes del commit |
| 21e | Commit, push, CI real y PR a `develop` | Tech Lead | opus/medium | Hecho: CI 8/8 en cd412cd; PR #11 mezclado en `develop` (dbf6bf0, sin squash); la rama sigue abierta |

**Modelos:** matriz del modo calidad. La sesión principal sigue en opus/medium (el modo pide opus/high; la usuaria puede subirlo con `/effort high`).

**Pendientes tras la iteración 21:**
- De Juanita: prueba manual con el arnés en modo real (gateway + servicio científico levantados) para WI-13 y la parte manual de WI-15 → cierra SEC-J-04 y confirma SEC-J-24 en navegador. BUG-J21-1 (hacer determinista la prueba de `_leaflet_pos`), BUG-J21-2 (`toContainText('(1)')` débil), BUG-J21-3 (asertar `defaultPrevented` con Espacio). SEC-J-I31 (opcional: `persist-credentials: false`, audit de dev). Mediciones HU-06 c.3 y HU-13 c.3.
- Santiago: avisarle del cambio aditivo en `web-interna/package.json` (D58); fijar `leaflet` 1.9.4 (SEC-J-I27 e I32: ya hay dos internos de Leaflet en uso); subir Playwright a 1.65.x en ambas webs a la vez.

**Modelos y consumo (iteración 21, modo calidad):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 21a–21e | 11.38 acumulado en la sesión (≈6.63 en la iteración 21; opus 9.61, sonnet 1.77 en total) | frontend-senior 21a (opus, 2.74); luego ciberseguridad 21d (opus, 1.11) y qa-tester 21c (sonnet, 1.00) | Ninguno fuera de la matriz de calidad |

---
# Iteración 22: mediciones de rendimiento de HU-06 c.3 y HU-13 c.3

**Fecha:** 2026-11-15 · **Rama:** `feature/ubicacion-consulta-publica` · **Modo de presupuesto:** equilibrado, sin tope.

**Alcance:** completar las tres historias de Juanita midiendo el único criterio que les falta a HU-06 y HU-13.
- HU-06 c.3 (CG-02): búsqueda pública con p95 ≤ 2 s, 10.000 fichas y caché fría. k6 sobre `compose-carga.yml`, con las reglas de evidencia de D42.
- HU-13 c.3 (CG-08, HB-23, DB-07): búsqueda interna con p95 < 3 s y 10.000 hallazgos cifrados. Prueba JUnit con Testcontainers y MockMvc, desactivada por defecto (`-Drendimiento=true`).
- Cada agente informa al Tech Lead, que consolida en `07-reporte-qa.md` y `trazabilidad.md`, para que ninguno edite los documentos compartidos.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 22a | HU-06 c.3 / CG-02: siembra parametrizable a 10.000, `k6/busqueda-publica-fria.js`, 3 corridas | qa-tester | sonnet/**high** | Hecho: **CUMPLE**. p95 en caché fría con 10.000 fichas: fase A 14,2 ms y fase B (10 VUs) 58,6 ms (medianas de 3 corridas; umbral 2.000 ms); 0 errores; FULLTEXT e índices en uso. 1 réplica con 2 CPU y 1 GiB, local y sin gateway (RQ-6) |
| 22b | HU-13 c.3 / CG-08: `BusquedaInterna10kRendimientoTest` (10.000 hallazgos cifrados, descifrados ≤ size, EXPLAIN), 3 corridas | qa-tester | sonnet/**high** | Hecho: **CUMPLE**. p95 ≤ 28,2 ms en los 6 casos (el Tech Lead lo repitió: ≤ 29,5 ms); descifrados = size; investigador sin coordenadas. DB-J22-1 (Baja): plan degradado por estadísticas del catálogo sin `ANALYZE` |
| 22c | Consolidar resultados, trazabilidad, commit y PR | Tech Lead | opus/medium | Hecho: `07` §22, `trazabilidad.md` (HU-06 y HU-13 → Cumple); CI 8/8; PR #12 mezclado en `develop` (0698756, sin squash) |

**Cambios de modelo:** qa-tester sonnet/**high** (matriz equilibrado: medium) en 22a y 22b, porque el método de medición tiene que ser riguroso para que cuente como evidencia (D42). Si no se cumple un umbral, la causa la analiza el dba antes de tocar índices.

**Pendientes tras la iteración 22:**
- dba: DB-J22-1 (`ANALYZE TABLE` de `ubicacion_departamento` y `ubicacion_region` tras las migraciones, para que el optimizador use los índices de investigador+fecha y especie+fecha).
- Si el despliegue real tiene menos de 2 CPU por réplica, repetir HU-06 c.3 con `CARGA_CPUS`.

**Modelos y consumo (iteración 22):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| 22a–22c | 14.19 acumulado en la sesión (≈2.81 en la iteración 22; opus 11.10, sonnet 3.09 en total) | los dos qa-tester (sonnet/high) | qa-tester sonnet/**high** (matriz: medium): rigor del método de medición |

---
# Iteración 23: revisión general antes del front (Santiago)

**Fecha:** 2026-11-16 · **Rama:** `develop`.

**Alcance:** revisar `develop` completo contra el enunciado, el SRS y el SAD, y cerrar lo que faltaba antes de empezar el front.

| # | Tarea | Estado |
|---|---|---|
| 23a | Revisión: pruebas de todo el backend y recorrido de punta a punta con los 5 servicios | Hecho: 32 de 34 comprobaciones; las 2 fallas eran reglas de roles y de custodio, corregidas en 23e y 23f |
| 23b | Evidencias: fotos y audios (RF1, HU-01 c.2, D62) | Hecho |
| 23c | Reporte mensual asíncrono con índice de presión (HU-09, RF15, RF16, D61) | Hecho |
| 23d | Historial bloqueado en la base (RNF10, D60) | Hecho en código y pruebas; **falta** que el dueño de Aiven corra el paso 5 de `09` §4.3 |
| 23e | Roles de escritura científica (D63) y autor = `preferred_username` (D59) | Hecho |
| 23f | Custodio `investigador_campo` y filtros combinados en equipos (HU-04 c.1) | Hecho |
| 23g | Arreglar las 2 pruebas de reportes y meter equipos y reportes al CI | Hecho |
| 23h | `Arnes.jsx` y `arnes.jsx` chocaban en Windows: el componente pasa a `ArnesUbicacion.jsx` | Hecho |
| 23i | Pantallas de `web-interna` con los diseños que elija Santiago | Hecho en la iteración 24 |

**Pendientes fuera del código:**
- Dueña del servicio de escritura en Aiven: correr `03-registro-historial-append-only.sql.example` (paso 5 de `09` §4.3).
- Todos: `docker compose up -d rabbitmq-usuarios` para los permisos nuevos de `reportes_mq`.
- Aceptados para el prototipo: outbox del publicador de eventos (`04` §14.6) y compose con todos los servicios (D34).

# Iteración 24: el front de las dos webs (Santiago)

**Fecha:** 2026-11-16 · **Rama:** `feature/front-galeria-cielo` (sale de `feature/revision-antes-del-front`).

**Alcance:** todas las pantallas de `web-interna` y el rediseño de `web-publica`, con los sistemas visuales de D64.

| # | Tarea | Estado |
|---|---|---|
| 24a | Sistema visual "galería" (`galeria.css`), ilustraciones SVG y pinturas de dominio público | Hecho |
| 24b | Esqueleto de la web interna: `react-router`, rutas perezosas, menú y guardas por rol (`permisos.js`), cliente HTTP con errores legibles | Hecho |
| 24c | Salas: inicio, búsqueda y mapa (módulo de Juanita), registrar hallazgo con atributos dinámicos y evidencias, detalle, reclasificar, historial, plantillas, equipos, reportes, usuarios y API keys | Hecho |
| 24d | Web pública: cielo de CORA y blanco y verde de ECOSIA, misma distribución que la interna | Hecho |
| 24e | Validación en navegador contra el backend real con los 5 roles, escritorio y móvil | Hecho: investigador, alto rango, analista y administrador recorridos; arreglados desbordes en móvil y textos |
| 24f | Pruebas: `App.test.jsx` nuevo (rutas, menú y guardas por rol) y las de ubicación y la web pública en verde | Hecho |

**Pendientes tras la iteración 24:** (ver también la iteración 25)
- Los de la iteración 23 fuera del código siguen en pie.
- La web pública en Vercel debe servir `public/pinturas/` (ya va dentro de `dist`).

# Iteración 25: segunda revisión del front con las notas de Santiago

**Fecha:** 2026-11-19 · **Rama:** `feature/revision-front-2`.

| # | Tarea | Estado |
|---|---|---|
| 25a | `levantar.ps1`: todo el entorno local con un comando, usuarios de prueba y datos de ejemplo | Hecho y probado con un `.env` de prueba |
| 25b | Arreglos que salieron al probarlo: CRLF del init de MySQL, espera de la API de RabbitMQ, usuario de migración de Aiven en local | Hecho |
| 25c | Fichas: foco al escribir, unidad más ancha, galería de especies con foto y paginación | Hecho |
| 25d | Listas desplegables (especie, familia, investigador) y catálogo público por especie con foto (D65) | Hecho |
| 25e | Mapa: leyenda, panel nuevo con foto | Hecho |
| 25f | Reportes: PDF con diseño y sala más didáctica | Hecho |
| 25g | Iconos, favicon, login de Keycloak, pie del portal, pinturas que rotan, salas con pintura | Hecho |
| 25h | Pruebas: catálogo y foto pública (científico y consulta), PDF, front unitario y E2E | Hecho |
| 25i | Tercera ronda: reportes viejos con el formato nuevo y CSV para Excel, flechas del carrusel, pinturas al azar por sala, Usuarios y roles rediseñada, nombre y cartela de cada sala en los bordes, login con fondos que rotan, fondos sin pixelado | Hecho |
| 25j | Más obras de historia de Colombia (E. W. Mark, Comisión Corográfica, Humboldt, Mutis) | Pendiente: Wikimedia bloqueó las descargas por exceso de peticiones |

**Pendientes tras la iteración 25:**
- Quien tenga Keycloak ya importado no necesita reimportar: `levantar.ps1` activa el tema del login por la API de administración.
- Al subir, avisar al equipo: cambian `docker-compose.yml` (monta el tema) e `infra/` (`.gitattributes` de MySQL y espera en `crear-usuarios.sh`).

---
# Iteración 26: revisión general del 2026-11-19 (solo lectura)

**Rama:** `develop` (`8d23981` → `14f140c`, mismo árbol). **Modo:** equilibrado, sin tope. Informe: `docs/revision/2026-11-19/INFORME.md`.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 26a | Quitar coautoría de Claude de 19 commits (develop + 5 feature) y restaurar protección | Tech Lead | opus | Hecho |
| 26b | Arquitectura, RNF y ASR | arquitecto-software | opus / high | Hecho (7,0) |
| 26c | RF y HU | product-owner | sonnet / medium | Hecho (8,0) |
| 26d | Backend | backend-senior | sonnet / high | Hecho (7,0) |
| 26e | Bases de datos | dba | sonnet / high | Hecho (7,5) |
| 26f | Frontend | frontend-senior | sonnet / medium | Hecho (7,5) |
| 26g | Seguridad | ciberseguridad | opus / high | Hecho (7,0, Rechazado por SEC-R-1) |
| 26h | DevOps | devops | sonnet / medium | Hecho (6,5) |
| 26i | QA: build, tests y app viva | qa-tester | sonnet / high | Hecho (8,5, Aprobado) |

**Modelos y consumo (iteración 26):**
| Fase | USD | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| R1 + R2 (8 informes y consolidado) | 15,06 (opus 10,30; sonnet 4,77) | arquitecto-software (4,22) | qa-tester sonnet/**high** (matriz: medium): levanta y prueba todo el sistema |

---
# Iteración 27: pendientes de Juanita de la revisión 2026-11-19

**Rama:** `feature/ubicacion-consulta-publica` (al día con `develop` `14f140c`). **Modo:** equilibrado, sin tope. **Fuente:** `docs/revision/2026-11-19/pendientes-por-persona.md` (sección Juanita). **Decisión nueva:** D66 (fotos públicas por evento), contrato 04 §4.4, §7.6 y §13.10.

| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 27a | D66 lado científico (miniatura al publicar, eventos `evidencia.*`, republicación, quitar ruta sin rol), BE-R-1/SEC-R-2 (un solo detector, listener sin 500), DB-J22-1 (`ANALYZE TABLE`) | backend-senior | sonnet / high | Hecho: `verify` 605 tests, 0 fallos (3 omitidos: broker/BD externos). Migraciones `V20261121_1000` y `_1010`. Falsos positivos nuevos: "long. 12 cm", "lat 3" |
| 27b | D66 lado consulta-publica (cola `consulta-publica.fotos`, `foto_publica`, rutas de foto, `fotoId`), ARQ-R-5 (monitor en `/api/publica/**` y catálogo degradado), invalidación de caché acotada, QA-R-5 (`familia=` vacío) | backend-senior | sonnet / high | Hecho: `verify` 406 tests, 0 fallos. Migración `foto_publica`. QA-R-5 ya ignoraba el vacío (solo se agregaron pruebas). Búsquedas por TTL |
| 27c | web-publica: FE-R-1 (foto por `fotoId`, sin 404 falsos), FE-R-5 (fondos de la portada), QA-R-3 (E2E intermitente) | frontend-senior | sonnet / medium | Hecho: 55 unitarios, build OK, E2E 38/38 ×3. Pendiente al integrar: `fotoId` real, CORS de la foto, `blob:` en CSP |
| 27d | Mediciones k6 con metadatos D42: ASR1 (incluida la portada) y ASR4 | qa-tester | sonnet / high | Hecho: ASR4 CUMPLE (p95 7,2 ms con 30 eventos/s). ASR1 CUMPLE en fichas y en la portada a 60 visitas/s; CUMPLE CON RESERVAS en la portada a 120 visitas/s con 2 réplicas (visita completa hasta 10 s; el cuello son las fotos leídas de MySQL; una caché de fotos en NGINX lo elimina: decisión pendiente del arquitecto). Detalle en 07 |
| 27e | Verificación QA de 27a-c y 27g | qa-tester | sonnet / **high** | Aprobado: científico 654, consulta-publica 408, web-publica 55 unitarios y E2E 38/38 ×3; Bruno 132/133 (el fallo es de datos locales). Verificación en vivo de D66, CORS y CSP OK. Bajos: OBS-I27-1 (`creadoEn` null en el 201 de evidencias), OBS-I27-2 (409 y fallos en segundo plano de `republicar` sin documentar), OBS-I27-3 (404 sin problem+json) |
| 27f | Auditoría de seguridad de D66 y del detector | ciberseguridad | opus / high | Aprobado con observaciones: 0 Crít/Alt. Medios SEC-I27-1 (regresiones del detector unificado: "5.532, 73.367", "5°31N", Garmin) y SEC-I27-2 (claves/números en atributos); Bajo SEC-I27-8 (CSP del README) |

**Coordinación:** D65 lo diseñó Santiago; avisarle de D66 antes del PR. BE-R-1 toca `registro` (Hernando): avisarle también.

**Modelos y consumo (iteración 27):**
| Fase | USD acumulado | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| Construcción (27a-c) | 5,61 (sonnet 3,89; opus 1,72) | backend-senior consulta-publica (1,97) | Ninguno: todo según la matriz equilibrada |

**Decisiones a llevar al grupo:** (1) el detector unificado rechaza "long. 12 cm" y "lat 3" (Hernando); (2) una foto retirada puede seguir hasta un año en navegadores por `immutable` (D66); (3) búsquedas y catálogo tardan hasta ~90 s en reflejar un cambio (TTL).

**Ciclo de corrección 1 (seguridad):**
| # | Tarea | Responsable | Modelo | Estado |
|---|---|---|---|---|
| 27g | SEC-I27-1 (detector endurecido en las 2 copias + paridad), SEC-I27-2 (claves y pares numéricos en atributos y plantillas), SEC-I27-7 (log del administrador y 409), SEC-I27-8 (`blob:` en la CSP del README) | backend-senior | sonnet / high | Hecho en la copia `/home/toji/biohub-fix27`: científico 654 tests y consulta-publica 408, 0 fallos. Corpus 41/41 detectados, 37/37 legítimos. **Pasado a la rama el 2026-11-22** (solo código, tests y README de web-publica; `pruebas-carga/` de la rama es más nuevo y se conservó) |
| 27h | Re-verificación de seguridad del ciclo 27g | ciberseguridad | opus / high | Aprobado con observaciones: SEC-I27-1/2/7/8 cerrados. Nuevos: Medios SEC-I27g-1 (formatos no detectados: espacios múltiples, hemisferio en minúscula, GMS sin °, UTM/MGRS, Plus Codes) y SEC-I27g-2 (claves `norte`/`este`, camelCase, tope de 64); Bajos SEC-I27g-3 (Unicode) y SEC-I27g-4 (paridad de corpus entre módulos). Pasan a la siguiente iteración |
| 27i | Partes rápidas de SEC-I27g-1 (espacios, hemisferio en minúscula, GMS sin °, longitud primero) y SEC-I27g-2 (camelCase/punto en claves, `norte`/`este`), pedidas por Juanita antes del PR | backend-senior | sonnet / high | Hecho en `biohub-fix27`: científico 677 tests y consulta-publica 408, 0 fallos (3 omitidos de siempre). Corpus 65 detectados / 57 legítimos / 9 dudosos. Nuevos falsos positivos: `talla 5 12 n`, `n 12 3.5`, `70.250, 3.100 g`. Pasado a la rama; el Tech Lead corrió `verify`: científico 680 (3 omitidos) y consulta-publica 408, 0 fallos |

Pendiente para el grupo: la regla de pares numéricos rechaza `{"largo": 1.234, "ancho": 2.345}` (≥ 3 decimales y en rango). Si molesta, exigir ≥ 4 decimales fuera del rango de Colombia.

Cambio de modelo: 27e pasa a sonnet/**high** (matriz: medium) porque levanta y prueba todo el sistema y además consolida k6.

**Pasan a la siguiente iteración:** SEC-I27g-1 (UTM/MGRS, Plus Codes, enlaces de mapas), SEC-I27g-2 (tope de 64, `x`/`y` con grado, objetos hermanos, `geo_latitud`), SEC-I27g-3, SEC-I27g-4, OBS-I27-1..3, el tercer detector `DetectorCoordenadasEnTexto` y la decisión sobre la caché de fotos para ASR1.

**Falsos positivos nuevos para el grupo:** `talla 5 12 n`, `n 12 3.5`, `70.250, 3.100 g`.

| Fase | USD (sesión 2026-11-22) | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| Calidad y cierre (27d, 27e, 27h, 27i) | 5,14 (opus 2,95; sonnet 2,19) | qa-tester (1,75) | qa-tester sonnet/**high** (matriz: medium): sistema completo más k6 |

**Estado de Juanita (2026-11-22):** su parte de la revisión 2026-11-19 está **completa** (PR #18 hacia `develop`, lo mezcla ella). Las decisiones de grupo y los hallazgos de seguimiento (SEC-I27g-1..4, OBS-I27-1..3, caché de fotos para ASR1) quedan como seguimiento del grupo, sin asignar a Juanita.

**Ciclo de corrección 2 (CI del PR #18):** `QaEventosInvalidosDlqTest` fallaba al azar en GitHub. Causa raíz: el patrón `PLANAS` del detector unificado (nuevo en esta iteración) aceptaba una letra suelta pegada a 7 cifras, así que un UUID que empieza por `e` y 7 dígitos (unos 2 de cada 1.000) se tomaba por coordenada plana y el evento se descartaba en silencio (en producción, la ficha no se habría publicado). Severidad Alta. Corrección del Tech Lead: la letra sola `n`/`e` exige espacio, `:` o `=` antes del número; corpus con UUID legítimos y resumen del bloque actualizados en los dos servicios; 0 de 200.000 UUID marcados. `verify`: científico 677 (+3 omitidos) y consulta-publica 408, 0 fallos. Se revirtió el aumento del tiempo de espera de la prueba (no era la causa). Efecto en seguridad: ya no se detecta una plana escrita sin separador (`e1045678`); se acepta.

**Cierre de la iteración 27 (2026-11-22):** PR #18 mezclado en `develop` por Juanita (`800b198`), CI en verde (22/22). La parte de Juanita de la revisión 2026-11-19 queda **cerrada**. El seguimiento (SEC-I27g-1..4, OBS-I27-1..3, tercer detector y caché de fotos para ASR1) y las decisiones de grupo quedan sin asignar.

| Fase | USD (sesión 2026-11-22) | Agente que más consumió | Cambios de modelo (motivo) |
|---|---:|---|---|
| Corrección de CI y cierre | ver medición final | Tech Lead (depuración de causa raíz hecha directamente, sin subagente) | Ninguno |
