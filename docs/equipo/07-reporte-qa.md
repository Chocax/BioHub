# 07 – Reporte de QA del esqueleto

Fecha: 2026-10-21. Entorno: Compose con 7 contenedores, servicios Java desde sus jars (8080-8084), Vite en 5173/5174, Keycloak 26.8.1. No se modificó código ni configuración; solo se escribieron este reporte y `06-plan-pruebas.md`. Los IDs de casos son los del plan.

## Resumen
| Concepto | Cifra |
|---|---|
| Casos del plan | 69 |
| Pasa | 66 |
| Falla | 1 (SE-20) |
| No ejecutado | 2 (IG-10, IN-13) |
| `mvn -B package` | BUILD SUCCESS; 17 tests, 0 fallos, 0 errores (gateway 9 = 4 ProxyServiceTest + 5 GatewaySecurityTest; cientifico 2, equipos 2, consulta-publica 2, reportes 2) |
| `npm test` web-interna / web-publica | 2/2 y 2/2 (vitest 5.0.5) |
| `npm run build` ambas webs | OK (JS 248 kB y 220 kB sin comprimir) |
| `npm audit --omit=dev` | 0 vulnerabilidades en ambas |
| Cobertura de código | No disponible (no hay JaCoCo ni `vitest --coverage` configurado) |

Nota: `mvn package` reescribió los jars de `target/` mientras los servicios corrían desde ellos; tras eso los 5 `/actuator/health` siguieron en 200.

## Resultado por caso
### Unitarias / componentes
UC-01 a UC-11: **Pasa** (cifras arriba).

### Integración vía gateway
| ID | Resultado | Evidencia |
|---|---|---|
| IG-01 | Pasa | 25/25 respuestas 200; usuario y rol coinciden con el token (ej. `auditor1 -> ["auditor_cientifico"]`) |
| IG-02 | Pasa | `{"servicio":"servicio-consulta-publica","usuario":"anonimo","roles":[]...}` 200 |
| IG-03 | Pasa | Solo un rol de negocio por usuario; `fecha` ISO con Z |
| IG-04 | Pasa | 200 con query con `%20`, `%26`, `%C3%B1` y con `?` vacío |
| IG-05 | Pasa | POST JSON a `/api/cientifico/hallazgos` -> 404 del servicio; a `/ping` -> 405 del servicio |
| IG-06 | Pasa | PUT/DELETE/POST sin cuerpo/5 MB/chunked -> 404 del servicio (con 100-continue en el de 5 MB) |
| IG-07 | Pasa | `/api/inexistente/ping` y `/api/` -> 404 (cuerpo vacío) |
| IG-08 | Pasa | 405 y 404 JSON del servicio llegan intactos |
| IG-09 | Pasa | 200 `{"status":"UP","groups":["liveness","readiness"]}` en gateway y servicios |
| IG-10 | **No ejecutado** | Exige detener un servicio y se pidió no detener nada. Solo cubierto en código (`GatewayExceptionHandler`) y sin prueba unitaria del 502 |
| IG-11 | Pasa | `/ping/` y `/CIENTIFICO/ping` -> 404 |
| IG-12 | Pasa (limitado) | 200; no se pudo observar qué cabecera recibe el servicio |

### Seguridad
| ID | Resultado | Evidencia |
|---|---|---|
| SE-01 | Pasa | 401 en las 4 rutas |
| SE-02, SE-03, SE-04, SE-05, SE-06 | Pasa | 401 en todos (firma, payload, `alg:none`, basura, Basic, `Bearer ` vacío) |
| SE-07 | Pasa | Token admin1 con `exp`=1792625222: 200 a las 23:23:50; a las 23:28:15 (>exp+60 s) 401 en gateway (`gateway`, `cientifico`, `publica`) y directo a 8081 y 8083 |
| SE-08 | Pasa | Token `master`/`admin-cli`: 401 en las 5 rutas y en 8081 directo |
| SE-09 | Pasa | Token `admin-cli` del realm biohub (`aud` ausente): 401 en gateway y 8081; la validación de audiencia funciona |
| SE-10 | Pasa | `/api/publica/ping` con token basura o con firma mala -> 401 (no cae a anónimo) |
| SE-11 | Pasa | POST/PUT/PATCH/DELETE sin token -> 401 |
| SE-12 | Pasa | POST con token -> 405 |
| SE-13 | Pasa | HEAD sin token -> 401 (ver observación O-3) |
| SE-14 | Pasa | `Access-Control-Allow-Origin` igual al origen para 5173 y 5174 |
| SE-15 | Pasa | 403 "Invalid CORS request" para `evil.example`, `null`, `127.0.0.1:5173` (también sin token y en `/api/publica`) |
| SE-16 | Pasa | Preflight permitido 200 con Allow-Methods/Max-Age 3600; origen malo y `x-evil` -> 403 |
| SE-17 | Pasa | 8081, 8082, 8084: sin token 401, firma mala 401, token válido 200 |
| SE-18 | Pasa | 8083: sin token 200 anónimo; `/api/otra` sin token 401; firma mala 401 |
| SE-19 | Pasa | 401 |
| SE-20 | **Falla** | Ver BUG-01 |
| SE-21 | Pasa | `%zz`, `%00`, URL de 9000 caracteres -> 400 (HTML de Tomcat, ver BUG-02), sin 5xx |
| SE-22 | Pasa | `ñ` codificada 404; `;jsessionid` 401; `//api/...` 401; `/api//cientifico/ping` 401; sin 5xx |
| SE-23 | Pasa | `/actuator/env`: 401 sin token; 404 con token (no expuesto) |
| SE-24 | Pasa | `web-interna` password grant: 400 `unauthorized_client` |
| SE-25 | Pasa | `invalid_grant`; `client_credentials`: 401 `unauthorized_client` |
| SE-26 | Pasa | `.env` modo 600; `.env` en `.gitignore` |

### Infraestructura
| ID | Resultado | Evidencia |
|---|---|---|
| IN-01 | Pasa | 7/7 `healthy` (keycloak reporta "Up 9 minutes" frente a 14 de los demás: se reinició o recreó antes de mi sesión; sin impacto) |
| IN-02 | Pasa | version 1 `base` `success=1` en los 5 esquemas |
| IN-03 | Pasa | transaccional: cientifico+equipos; lectura: consulta_publica; analitica: reportes; gateway: gateway |
| IN-04 | Pasa | `cientifico_app` -> `equipos`: ERROR 1142 SELECT denied |
| IN-05 | Pasa | `equipos_app` -> `cientifico`: ERROR 1142 SELECT denied |
| IN-06 | Pasa | `GRANT ALL PRIVILEGES ON <su esquema>.*` y `USAGE ON *.*` |
| IN-07 | Pasa | `NOAUTH Authentication required` y `WRONGPASS` |
| IN-08 | Pasa | `PONG` |
| IN-09 | Pasa | 200 en `/api/overview` |
| IN-10 | Pasa | 401 sin y con credenciales malas |
| IN-11 | Pasa | `biohub.eventos`, topic, durable; 3 conexiones AMQP abiertas (servicios) |
| IN-12 | Pasa | Todos en 127.0.0.1 |
| IN-13 | **No ejecutado** | No existe aún endpoint que use la caché Redis desde servicio-consulta-publica |

### E2E navegador
E2E-01 a E2E-07: **Pasa**. Con investigador1 y con admin1: web-pública conectada; redirección a Keycloak con `S256`/`web-interna`; ping muestra usuario y rol correctos; sin token en storage; logout vuelve al login de Keycloak; consola sin errores.

## Bugs
Ninguno es Crítico ni Alto.

### BUG-01 (Medio) – Recorrido de directorios codificado escapa del prefijo autorizado en el gateway
- Responsable: backend (api-gateway). Archivo: `api-gateway/src/main/java/co/edu/unbosque/biohub/gateway/service/ProxyService.java:51-59` (usa `request.getRequestURI()` crudo y `build(true)` sin normalizar) y `resolverDestino` (:40-48).
- Reproducir: `GET http://localhost:8080/api/cientifico/%2e%2e/%2e%2e/actuator/health` con token válido; y sin token `GET http://localhost:8080/api/publica/%2e%2e/%2e%2e/actuator/health`.
- Esperado: el gateway rechaza (400/404). Obtenido: 200 `{"status":"UP",...}`. El gateway autoriza y enruta según la ruta cruda, pero Tomcat del servicio decodifica y normaliza a `/actuator/health`. La ruta pública llegó sin autenticación a una ruta no pública del servicio (aquí solo `health`, que ya es público; el riesgo es futuro: reglas por prefijo/rol en el gateway o rutas públicas del servicio se pueden saltar).
- Evidencia: salida de curl indicada (200). Con `/api/cientifico/%2e%2e/%2e%2e/actuator/env` el servicio respondió 404 con `"path":"/actuator/env"`, confirmando que la ruta efectiva salió de `/api/cientifico`.
- Corrección sugerida: en `ProxyService.reenviar` rechazar con 400 si la ruta (cruda o decodificada) contiene `..`, `%2e`, `%2f`, `%5c`, `;` o `\`; o normalizar con `URI.normalize()` y re-verificar que el resultado empieza por `/api/<prefijo>/`. Añadir prueba en `ProxyServiceTest`.

### BUG-02 (Bajo) – Formato de error inconsistente
- Responsable: backend (api-gateway). Archivos: `SecurityConfig.java` (401 sin cuerpo), `ProxyService.java:54` (`notFound().build()` sin cuerpo), Tomcat.
- Reproducir: `GET /api/cientifico/%zz` con token -> 400 con HTML de Tomcat; `GET /api/inexistente/ping` -> 404 sin cuerpo mientras `/api/cientifico/noexiste` -> 404 JSON `{"timestamp",...}`; origen no permitido -> texto plano "Invalid CORS request"; 401 sin cuerpo.
- Esperado: errores en un formato único (p. ej. `application/problem+json` como el 502 del contrato). Obtenido: cuatro formatos distintos. El contrato no los define, pero la web los tratará igual.
- Corrección sugerida: `ProblemDetail` para 404 de prefijo y un `AuthenticationEntryPoint`/`AccessDeniedHandler` JSON; documentar en el contrato.

### BUG-03 (Bajo) – Cabecera `Vary` duplicada en respuestas proxeadas
- Responsable: backend. Archivo: `ProxyService.java:73` (excluye `access-control-*` pero no `Vary`).
- Reproducir: `curl -i -H "Authorization: Bearer <t>" -X POST localhost:8080/api/equipos/algo`: aparecen `Vary:` y `vary:` repetidas (3 + 3 valores).
- Corrección sugerida: no copiar `vary` del servicio, o fusionar valores.

## Observaciones (no son bugs del esqueleto)
- O-1: `admin-cli` del realm `biohub` (cliente por defecto de Keycloak) permite password grant y emite tokens para usuarios del realm. Sus tokens no tienen `aud=biohub-api`, así que la API los rechaza (SE-09), pero conviene deshabilitar `directAccessGrantsEnabled` en ese cliente o dejar solo `biohub-pruebas`. Solo desarrollo.
- O-2: El preflight CORS de `/api/publica/**` anuncia POST/PUT/PATCH/DELETE (`SecurityConfig.java:61,66`) aunque el prefijo es solo GET; el servidor igualmente devuelve 401/405. Considerar CORS más estricto por prefijo cuando se implemente la API pública.
- O-3: HEAD a `/api/publica/**` da 401 (la regla es solo GET). Aceptable por contrato; decidir si se desea soportar HEAD.
- O-4: Spring Security admite 60 s de tolerancia de reloj: un token vencido sigue siendo válido hasta `exp`+60 s.
- O-5: No hay pruebas automáticas del 502 (servicio caído) ni de timeouts del gateway; el caso IG-10 quedó sin verificar en vivo.
- O-6: No hay medición de cobertura.

## Veredicto: APROBADO CON OBSERVACIONES
El esqueleto cumple el contrato: build y tests verdes (17 Java + 4 web), matriz 5x5 de pings correcta, autenticación robusta frente a tokens manipulados, vencidos, de otro realm y sin audiencia, servicios no accesibles sin JWT, CORS correcto, infraestructura sana con Flyway aplicado, aislamiento entre esquemas, Redis con contraseña y E2E con dos usuarios. No se rechaza porque no hay defectos Críticos/Altos y el único fallo (BUG-01, Medio) no expone hoy nada sensible, pero debe corregirse antes de añadir reglas de autorización por ruta o rutas públicas con datos. Quedan sin verificar: 502 con servicio caído (IG-10) y uso de Redis desde la aplicación (IN-13).

## Seguimiento del Tech Lead
| Bug | Resolución |
|---|---|
| BUG-01 | **Falso positivo, pero se reforzó.** Reproducido con `curl -v`: sin `--path-as-is`, curl normaliza `%2e%2e` en el cliente y envía `GET /actuator/health` (público). Con `--path-as-is` el gateway respondía 401 (firewall de Spring). Aun así se agregó validación de rutas no normalizadas en `ProxyService` (400) y `HttpStatusRequestRejectedHandler(400)`. Re-verificado: 400. |
| BUG-02 | **Mejorado**: prefijo inexistente → 404 ProblemDetail; URI inválida → 400 ProblemDetail; cuerpo grande → 413 ProblemDetail. 401 sin cuerpo y 403 CORS en texto quedan como están (Bajo). |
| BUG-03 | **Corregido**: el proxy ya no copia `Vary` del servicio; ahora solo salen los 3 `Vary` distintos de CORS. |
| IG-10 (502 no ejecutado) | Ejecutado por el Tech Lead en la primera verificación: servicio-reportes detenido → 502 ProblemDetail. |

Re-verificación completa tras `docker compose down -v`: 7/7 healthy, Flyway V1 en 5 esquemas, 5 pings 200, negativos OK, E2E navegador OK con investigador1 y admin1.
Tests automatizados: Maven 31 (gateway 23: ProxyServiceTest 18 + GatewaySecurityTest 5; 2 por servicio), web-interna 2, web-publica 2.

---
# Iteración despliegue + RabbitMQ (tarea 13e)
Fecha: 2026-10-22. Rama `feature/despliegue`. Casos en `06-plan-pruebas.md` (secciones 6-9).

## Resumen de ejecución
| Prueba | Resultado |
|---|---|
| `mvn -B verify` (raíz), con Docker | BUILD SUCCESS. **73 tests, 0 fallos, 0 errores, 0 omitidos** |
| api-gateway | 23 |
| servicio-cientifico | 16 (Testcontainers: `PublicadorEventosIntegracionTest` 2) |
| servicio-equipos | 2 |
| servicio-consulta-publica | 16 (Testcontainers: 2 clases IT) |
| servicio-reportes | 16 (Testcontainers: 2 clases IT) |
| `npm test` web-interna / web-publica | 2 / 2 pasan (sin cambios) |
| Tests opt-in contra RabbitMQ con las definitions de despliegue | 2 de 2 pasan |

- Backend-senior reportó 71; la primera corrida de `verify` dio 71 (0 omitidos: los Testcontainers sí se ejecutaron; se vieron arrancar los contenedores `rabbitmq:4-management`). Los 2 adicionales son tests agregados por QA (abajo). No se midió cobertura (sin JaCoCo en el proyecto).
- Tests agregados por QA (solo `src/test`):
  - `servicio-reportes/.../eventos/ConsumidorEventosRechazosIntegracionTest` y `servicio-consulta-publica/.../eventos/ConsumidorEventosRechazosIntegracionTest`: con broker real, un evento cuya routing key (`hallazgo.registrado`) difiere de su `tipo` (`prueba.ping`) y otro de más de 64 KB terminan en la DLQ, la cola principal queda vacía y no se almacena nada. Antes solo estaban cubiertos a nivel unitario.
  - Opt-in (se omiten sin `QA_RMQ_PORT`): `ConsumidorBrokerDesplegadoTest` (reportes) y `PublicadorBrokerDesplegadoTest` (científico), que usan los usuarios `reportes_mq` y `cientifico_mq`.
- Problema del propio test/entorno corregido: mi primera corrida opt-in falló con `ACCESS_REFUSED` porque yo leí la contraseña de la plantilla incluyendo el comentario de fin de línea; no era un defecto del despliegue (con la contraseña limpia pasan).

## Cobertura de criterios del evento
Cubiertos y verdes: POST 202/400/401/403/503; GET recibidos 401/403/200 en ambos consumidores; límite de 20 y orden; DLQ por JSON roto, tipo desconocido, routing key distinta del tipo y >64 KB (con broker real); cabecera `__TypeId__` ignorada (el IT envía una clase inexistente y el evento se consume); mensaje exacto de 200 válido y 201 inválido.
Verificado además contra un RabbitMQ real cargado con el `definitions.json` generado por `generar-definitions.sh` (con contraseñas ficticias, en contenedor local, sin tocar ningún entorno compartido): 4 usuarios (`biohub` administrator, `cientifico_mq`, `consulta_mq`, `reportes_mq`), sin `guest`; `reportes_mq` declara su topología, consume y envía a DLQ; `cientifico_mq` publica con confirmación y recibe `ACCESS_REFUSED` al leer `reportes.eventos`; el evento publicado por `cientifico_mq` llegó a `reportes.eventos` (1 mensaje, cola durable con los argumentos DLX esperados). `consulta_mq` no se probó en vivo (su regla es simétrica a la de `reportes_mq`).

## Artefactos de despliegue (estático)

Sección retirada en la iteración 15 (VPN de malla eliminada; ver 09-despliegue.md).

## Bugs
No se encontraron bugs Críticos, Altos ni Medios. Hallazgos menores:

BUG-04 (`.env.example` de `deploy/`): sección retirada en la iteración 15 (VPN de malla eliminada; ver 09-despliegue.md).

### Observaciones (para ciberseguridad / Tech Lead)
- O-9: Los tests opt-in dependen de variables de entorno; no hay prueba automática en CI de las definitions reales. Sugerencia: añadir un IT que cargue `definitions.json` generado en Testcontainers.
- O-10: Sin cobertura medida (JaCoCo) ni E2E automatizado del flujo de eventos por el gateway; queda el procedimiento manual (plan, sección 9).

## Veredicto: APROBADO CON OBSERVACIONES
El código de RabbitMQ cumple el contrato (73/73 tests con broker real, 0 omitidos) y los permisos por usuario funcionan contra un RabbitMQ real con las definitions generadas. La verificación de los artefactos de despliegue en VMs fue retirada en la iteración 15 (VPN de malla eliminada; ver 09-despliegue.md).

---
# Iteración 15h – verificación final (2026-11-04)
Rama `feature/ubicacion-consulta-publica`, con los cambios sin commit del árbol de trabajo (refactor de patrones en ubicación, degradación automática, verificación del publicador AMQP activa por defecto, `pruebas-carga/`). Todo se ejecutó en esta sesión; no se confió en el reporte de backend.

## 1. Backend (`mvn -o test`, Docker disponible, Testcontainers reales)
| Módulo | Tests | Fallos | Errores | Omitidos | Resultado |
|---|---|---|---|---|---|
| servicio-cientifico | **229** | 0 | 0 | **1** | BUILD SUCCESS (1 min 41 s) |
| servicio-consulta-publica | **190** | 0 | 0 | 0 | BUILD SUCCESS (1 min 37 s) |

- Coinciden exactamente con lo reportado por backend-senior. El omitido es `PublicadorBrokerDesplegadoTest` (opt-in: requiere `QA_RMQ_PORT/USER/PASS`); no es un fallo.
- Corridos con el cambio del Tech Lead ya aplicado (`verificar-publicador:true` por defecto en `ConsumidorEventos`); `PublicadorNoPermitidoIntegracionTest` (4) pasa.
- No se midió cobertura (el proyecto no tiene JaCoCo).

## 2. web-publica (como CI)
| Paso | Resultado |
|---|---|
| `npm run build` | OK (121 módulos) |
| `npm test` (Vitest) | **32/32** (3 archivos) |
| `npm audit --omit=dev --audit-level=high` | 0 vulnerabilidades |
| E2E Playwright, Chromium + Firefox | **28/28** con la suite original; con los 2 tests nuevos de QA (abajo) quedan 15 tests x 2 navegadores = 30 |
| E2E Playwright, WebKit | **No ejecutable en esta máquina**: 14/14 fallan en `browserType.launch: Host system is missing dependencies` (faltan librerías del sistema; `sudo npx playwright install-deps webkit`). Es limitación del entorno, no de la app; CI sí instala `--with-deps`. Se corrió con `E2E_SIN_WEBKIT=1`, que la propia config prevé. RNF13 en Safari queda sin verificar localmente |
| `web-interna` `npm test` (páginas de ubicación, fuera de CI) | 9 pasan, **2 archivos no cargan** (`PaginaBusqueda.test.jsx`, `PaginaMapa.test.jsx`): `@testing-library/user-event` está en `package.json` pero no instalado en este `node_modules`. Es de entorno (falta `npm ci`), no un bug de código; no se corrigió. Nota: CI no corre los tests de web-interna |

Tests agregados por QA (solo `web-publica/e2e/portal.spec.js`, bloque "QA 15h"): **WP-14** (texto malicioso `<img onerror>`, `<script>`, `<svg onload>` en los datos se muestra como texto, no se ejecuta, sin diálogos) y **WP-08** (búsqueda y ficha sin `Authorization`, `localStorage`/`sessionStorage`/cookies vacíos). Ambos pasan en Chromium y Firefox.

## 3. Colección Bruno (viable y ejecutada)
Se reprodujo el job de CI con la infraestructura del compose del repo ya levantada (Keycloak, RabbitMQ, Redis) y un MySQL 8.4 **efímero** en el puerto 3399 (`qa-mysql-bruno`, creado con `infra/ci/init-mysql.sql`; ya eliminado), sin tocar los MySQL de desarrollo. Los dos servicios se arrancaron con los jar recién empaquetados, perfil `local`, llave ficticia de CI y `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO` = usuario de RabbitMQ (`rabbitUser` del entorno `local` de Bruno), es decir verificación activa. Se sembró con `infra/ci/SembrarHallazgos.java`.
- `bru run --env local`: **44/44 requests, 77/77 tests, PASS** (10,5 s). Incluye los 4 eventos `setup/` con `user_id`, el caso de coordenadas (el servicio registró el incidente SEC-J-03 y lo descartó, esperado) y recifrado 401/403/200.
- Servicios detenidos y contenedor eliminado al terminar. Con `--env ci` no se corrió (usa el usuario `biohub_ci`, que no existe en este broker); el entorno `local` es el equivalente.
- Aviso menor: pasé `--reporter-junit` a un directorio inexistente y bru devolvió código 2 después de pasar todo; no es un fallo de la colección (CI usa `resultados/`, que se crea).

## 4. `pruebas-carga/`
- Sintaxis de los `.sh` correcta (`bash -n`). Humo ejecutado con `construir-imagen.sh` + `compose-carga.yml` (1 réplica + NGINX) + `datos/sembrar.sh` (2000 fichas) + `correr-k6.sh k6/consulta-publica-pico.js -e N=2 -e MULT=5` con tiempos cortos: 168 peticiones, 0 % no-200, 0 % 5xx, p95 10,6 ms. Los scripts de pico **funcionan y son reproducibles** con esos comandos. Después: `down -v`, `limpiar.sh` (0 fichas) y borrado del JSON de humo. No se interpreta contra el ASR (lo hace el arquitecto).
- Se probó también `carga-cientifica-simultanea.js` (CIENTIFICA=1, 15 s): ver BUG-J2.
- BUG-J3: el README está desactualizado (abajo).

## 5. Bugs
**BUG-J2 – Media – responsable: backend (carga) / Tech Lead.** La prueba `carga-cientifica-simultanea.js` (ASR4/RNF5, CG-04) ya no mide lo que dice: con la verificación del publicador activa por defecto, consulta-publica rechaza todos sus eventos.
- Pasos: `./construir-imagen.sh`; `docker compose -p biohub-carga -f compose-carga.yml --env-file ../.env up -d --scale consulta-publica=1`; `./datos/sembrar.sh`; `./correr-k6.sh k6/carga-cientifica-simultanea.js -e CIENTIFICA=1 -e R=10 -e EV=5 -e DUR=15s`.
- Esperado: los eventos se procesan (SELECT ... FOR UPDATE + INSERT, invalidación de caché) mientras se mide la lectura pública; DLQ vacía (CG-04).
- Obtenido: k6 reporta `eventos=76 ok=100 %` (solo mide que el broker enrutó), pero el servicio registró 228 veces `Evento hallazgo.* con publicador no permitido` y la DLQ `consulta-publica.eventos.dlq` quedó con 76 mensajes. La carga de escritura nunca llega a la BD, así que la medición ASR4 sería optimista y engañosa.
- Causa: la publicación por la API de management (`k6/carga-cientifica-simultanea.js`, ~línea 104) no pone `user_id` en `properties` y `compose-carga.yml` no define `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO`. Arreglo: poner `user_id: RMQ_USER` en las propiedades (RabbitMQ solo lo acepta si coincide con el usuario autenticado) y pasar `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO: ${RABBITMQ_USER}` al servicio en el compose. Además el script debería comprobar `cola_dlq == 0` y fichas resultantes, no solo `routed`.
- Efecto secundario de mi prueba: dejé 76 mensajes en la DLQ del RabbitMQ de desarrollo; intenté purgarla y el permiso fue denegado, así que **queda pendiente purgar `consulta-publica.eventos.dlq`** (borrar contenido desde la UI de management o `rabbitmqctl purge_queue`).

**BUG-J3 – Bajo – responsable: backend / Tech Lead.** `pruebas-carga/README.md` sigue diciendo "Carpeta reservada... Hoy está vacía: no hay scripts implementados" y no explica construir imagen, compose efímero, siembra, `correr-k6.sh` ni los parámetros. Los scripts no son reproducibles "según su README" porque el README no los describe (sí lo son leyendo las cabeceras de los scripts y del compose). `docs/equipo/trazabilidad.md` (líneas 9 y 111) también afirma que no hay scripts de carga ni colección Bruno.

Observaciones (no bugs):
- O-11: con el `.env` de desarrollo (`RABBITMQ_USER=biohub`), si se arranca consulta-publica sin `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO=biohub` (el default es `cientifico_mq`), todo evento de cientifico se rechaza. `.env` no define esa variable ni `.env.example` explica el caso local. Documentarlo en el README de consulta-publica / 09-despliegue.
- O-12: no hay bugs de severidad Crítica o Alta. Sin cobertura medida (JaCoCo).

## 6. Casos del plan 06 (parte de Juanita, P3–P9) sin test automatizado propio
Los tests no citan los IDs del plan (`CF-01`, etc.), así que no existe trazabilidad mecánica; el mapeo se hizo por área y contenido de las clases de test (229 + 190 + 32 + 15 E2E + 44 requests Bruno). Cobertura por área: cifrado/llaves, `UbicacionService`, cifrado en reposo, búsqueda interna, mapa, recifrado, JWT, auditoría, inyección/límites, consumo de eventos, DLQ, búsqueda pública, caché, Redis caído y degradación (automática y forzada) tienen tests. Sin cobertura automatizada o con cobertura solo parcial:
- **CF-06** (UUID en mayúsculas en el AAD): no se encontró test explícito.
- **LG-01/LG-02** (fuga por logs a nivel TRACE/DEBUG con captura de log): cobertura solo parcial (auditoría y "sin filtraciones", no barrido TRACE de Hibernate).
- **CG-01..CG-11** (carga): solo hay k6 para pico x60 (≈CG-03/05), carga científica simultánea (CG-04, afectado por BUG-J2) y registro; sin script para CG-02, CG-06 (Redis caído en pico), CG-07 (degradado en pico), CG-08 (`-Dgroups=rendimiento`: no existe ese grupo), CG-09, CG-10; CG-11 bloqueado por rate limit.
- **WP-15** (axe-core), **WP-16** (responsive), **WP-18** (humo real con compose), **WP-11** (matriz de navegadores incl. móvil real y Edge) y **UX-01..UX-05** (usabilidad, manuales): sin automatizar; WP-11 en WebKit además no ejecutable aquí. WP-10 (consola limpia) y WP-12/WP-13 solo parcialmente (Vitest y `erroresCsp`, no E2E completo).
- Ahora **cubiertos por QA 15h**: WP-14, WP-08.
- Bloqueados por dependencias de otros equipos (sin cambios): IE-01..IE-04, SG-17..SG-19, CG-11, WI-13, DX-01..DX-04 (requieren autorización y despliegue; VPN y VMs ya retiradas, estos casos están obsoletos).
- Casos de `web-interna` (WI-*, WC-*): 9 tests pasan, 2 archivos no ejecutables por dependencia no instalada (ver §2); no verificados.

## Veredicto 15h: APROBADO CON OBSERVACIONES
Backend 229 + 190 tests verdes (0 fallos, 1 omitido opt-in), web-publica 32 unitarios + 30 E2E (Chromium/Firefox) + build + audit verdes, Bruno 44/44 (77/77 tests) con la verificación del publicador activa, y el humo de la carga de pico funciona. No hay bugs Críticos ni Altos. Se aprueba con reservas porque: BUG-J2 (Medio) invalida la prueba de carga científica simultánea tal como está (y dejó 76 mensajes en la DLQ de desarrollo por purgar), BUG-J3 (documentación de carga), WebKit no verificado localmente, y los casos sin automatizar de §6. BUG-J2 debe corregirse antes de usar `carga-cientifica-simultanea.js` como evidencia del ASR4.

---

## Re-verificación ciclo 1 (2026-11-04)

Todo lo de abajo se ejecutó en esta sesión (no se tomó del reporte de backend). Sin commits; solo se añadió un test opt-in de QA.

### Resultados

| Verificación | Resultado |
|---|---|
| `mvn -o test` servicio-cientifico | **239 tests, 0 fallos, 0 errores, 1 omitido** (el opt-in de broker desplegado). BUILD SUCCESS. Coincide con el reporte. |
| `mvn -o test` servicio-consulta-publica | **190 tests, 0 fallos, 0 errores, 0 omitidos**. BUILD SUCCESS. Coincide. |
| Humo `carga-cientifica-simultanea.js` (`CIENTIFICA=1 R=10 EV=5 DUR=15s`, 2 réplicas, siguiendo solo el README) | Públicas 151 (p95 19,7 ms, 0 % no-200, 0 % 5xx). **Eventos 76 publicados, 76 procesados, cola pendiente 0, DLQ de carga 0.** Logs de las 2 réplicas: **0** apariciones de "publicador no permitido" y 0 de "descart". |
| Aislamiento de la cola de desarrollo | `consulta-publica.eventos` = 0 y `consulta-publica.eventos.dlq` = 76 (los viejos), **sin cambios** antes y después. Las réplicas de carga consumían `consulta-publica.carga.eventos` (2 consumidores). |
| Limpieza | `down -v` y `datos/limpiar.sh`: 0 fichas de carga; el JSON de humo se borró; la cola/DLQ/exchange de carga ya no existen en RabbitMQ. |
| E2E real científico -> consulta-publica (ARQ-26) | **OK**, ver abajo. |
| Bruno `--env local` (CLI 4.3.0, MySQL efímero 3399, ambos servicios con los jar actuales y `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO=biohub`, verificación activa) | **44/44 requests, 77/77 tests, PASS** (9,4 s). Servicios detenidos y contenedor eliminado. |

### E2E ARQ-26 (sin inyectar `user_id` a mano)
No existe flujo real que publique `hallazgo.*` desde servicio-cientifico: el único endpoint que usa `PublicadorEventos` es `POST /api/cientifico/eventos/prueba` (`prueba.ping`, solo administrador), y el módulo de registro (HU-01) es de otro equipo. Se usó entonces el `PublicadorEventos` REAL (el mismo bean, con su `user_id` tomado de la conexión) desde un test opt-in nuevo: `servicio-cientifico/src/test/java/co/edu/unbosque/biohub/cientifico/eventos/PublicadorHallazgoE2ETest.java` (se activa con `QA_HALLAZGO_ID` + `QA_RMQ_PORT/USER/PASS`; sin esas variables se omite, por lo que no altera los 239 tests).
- Se arrancó consulta-publica (jar actual, perfil `local`, MySQL de desarrollo, `BIOHUB_EVENTOS_VERIFICAR_PUBLICADOR=true`, `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO=biohub`, valores de `.env.example`) y se publicó un `hallazgo.registrado` con UUID único.
- Resultado: `GET /api/publica/fichas/{id}` devolvió 200 con la ficha completa (`ubicacionProtegida: true`, sin coordenadas); `consulta-publica.eventos` quedó en 0 y la DLQ de desarrollo siguió en 76 (no la usé para medir). Log del servicio sin WARN/ERROR de publicador. Con esto la cadena `user_id` AMQP del publicador -> verificación del consumidor funciona sin Bruno. Se eliminó la ficha de prueba de la BD de desarrollo.
- Límite: no se ejercitó el endpoint HTTP de servicio-cientifico con un JWT de administrador (publica `prueba.ping`, que no es `hallazgo.*`); la cobertura de ese camino es `EventosPruebaControllerTest` y `PublicadorEventosIntegracionTest`, que pasan.

### Estado de los hallazgos
- **BUG-J2: CORREGIDO.** El humo reproduce lo que describía el bug (antes 228 rechazos y 76 mensajes en DLQ) y ahora da 76/76 procesados, DLQ de carga 0, 0 rechazos por publicador, sin tocar las colas de desarrollo. El script además comprueba la DLQ de carga y reporta procesados/pendientes.
- **BUG-J3: CORREGIDO (parcial en otro documento).** Seguí `pruebas-carga/README.md` tal cual (construir jar e imagen, compose con `--scale`, siembra, k6, apagar y limpiar) sin conocimiento previo y funcionó a la primera; el orden `down -v` antes de `limpiar.sh` está explicado. Pendiente menor (no bloquea): `docs/equipo/trazabilidad.md` (líneas 9 y 176) sigue diciendo que `pruebas-carga/` solo tiene README y que no existen `.github/` ni la colección Bruno; es un documento ajeno al README.
- **O-11: CERRADA.** `.env.example` raíz trae `BIOHUB_EVENTOS_VERIFICAR_PUBLICADOR=true` y `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO=biohub` (igual a `RABBITMQ_USER`); con esos valores el E2E y Bruno pasan con verificación activa.
- **Pendiente de usuario (heredado):** purgar `consulta-publica.eventos.dlq` (76 mensajes de mi prueba anterior).

### Bugs nuevos
Ninguno (sin BUG-J4). Observación de entorno: los jar de `target/` se reescribieron al empaquetar (no afecta a nada en ejecución).

### No ejecutado
WebKit local (limitación de librerías del sistema, igual que antes), web-publica/web-interna (fuera del alcance del ciclo, sin cambios), pico x60 y par `CIENTIFICA=0/1` completo (solo humo, por instrucción), y el endpoint HTTP de publicación con JWT de administrador sobre el servicio científico en ejecución.

### Veredicto ciclo 1: APROBADO
Los tres hallazgos (BUG-J2, BUG-J3, O-11) quedan cerrados con evidencia ejecutada, 239 + 190 tests en verde, Bruno 44/44, humo de carga limpio y ARQ-26 validado de punta a punta con el publicador real. Sin bugs nuevos.

### CI en GitHub, primera ejecución (2026-11-04, run 41876849186)
- backend cientifico y consulta-publica: OK. Bruno: 44/44 requests, 77/77 tests, pero el job salía con código 2 porque Bruno CLI no crea `resultados/` → `mkdir -p resultados` en `ci.yml`.
- web-publica: 2 E2E fallaban solo en WebKit por `style-src-attr` "inline" en `<select>` nativos: artefacto de WebKit (se reproduce con un `<select>` estático en HTML puro con `style-src 'self'`), no de la app. El fixture `erroresCsp` ahora usa el evento `securitypolicyviolation` e ignora solo esa combinación exacta; 3 tests de guarda demuestran que `<style>`, `style=` y `<select style=...>` siguen detectándose. WebKit verificado en Docker (`mcr.microsoft.com/playwright:v1.64.1-noble`): 18 pasan, 1 omitido previo. CSP sin cambios (sin `'unsafe-inline'`).

## Iteración 16 (2026-11-04): verificación de 81f4e28..HEAD

Commits revisados: ARQ-33 (81f4e28), SEC-J-04 web-interna (47b5cc9), SEC-J-13 (786f4e1), SEC-J-12 (57ab7ad), SEC-J-08.3 (1e449ac), ARQ-29 (25d2175), ARQ-28 (aeb67d8).

### Bug encontrado y corregido

**BUG-J4 (Medio, responsable: QA, solo tests): `mvn verify` a secas falla con un `.env` local del perfil Aiven.**
- Pasos: con un `.env` que define `CIENTIFICO_MIGR_USER/PASSWORD` (o `CONSULTA_MIGR_*`) en la raíz o en el servicio, ejecutar `mvn -B verify` en `servicio-cientifico` o `servicio-consulta-publica`.
- Esperado: las pruebas son herméticas y no dependen del entorno del desarrollador. Obtenido: `spring.config.import` carga el `.env`, Flyway usa `cientifico_migr` contra el MySQL de Testcontainers y falla con `Access denied for user 'cientifico_migr'` (científico: decenas de errores de contexto, p. ej. MigracionesUbicacionTest 6/6 y QaSeguridadJwtTest 30/30; consulta-publica: igual). En CI no se ve porque no hay `.env`.
- Primer intento descartado: sobrescribir `spring.config.import` en un `application.properties` de pruebas no sirve, porque Spring procesa los imports de cada archivo de configuración aunque otro tenga precedencia.
- Corrección: `src/test/resources/application.properties` en ambos servicios con `spring.flyway.user=${spring.datasource.username}` y `spring.flyway.password=${spring.datasource.password}`. Solo afecta al classpath de pruebas; producción intacta; no se leyó ni copió ningún valor de los `.env`.
- Evidencia tras la corrección, con el `.env` real presente y sin variables sobrescritas: científico 264 ejecutadas, 0 fallos, 0 errores, 2 omitidas, BUILD SUCCESS; consulta-publica 194, 0, 0, 0 omitidas, BUILD SUCCESS.

### Resultados

| Proyecto | Comando | Resultado |
|---|---|---|
| servicio-cientifico | `mvn -B verify` | 264 / 0 fallos / 0 errores / 2 omitidas |
| servicio-consulta-publica | `mvn -B verify` | 194 / 0 / 0 / 0 omitidas |
| web-interna | `npm test` y `npm run build` | 46 de 46 (4 archivos); build OK |
| web-publica | `npm test` y `npm run build` | 32 de 32 (3 archivos); build OK |

Las 2 omitidas de científico son las pruebas opt-in contra el broker desplegado (requieren `QA_RMQ_*`).

### Revisión de que las pruebas nuevas prueban lo que dicen

- SEC-J-13: `HallazgoRegistradoSerializacionTest` comprueba JSON y `convertValue` sin claves ni valores de coordenadas, y que siguen en memoria. `PublicadorEventosTest` cubre claves (6 variantes, sin distinguir mayúsculas), anidadas (mapa, lista, mapa), texto (decimal, grados, "lat:/lon:"), que el mensaje no filtra el valor, que no se llama al broker, un caso sin coordenadas con números con coma que se publica (sin falso positivo) y que `prueba.*` no se revisa. Sin hueco relevante.
- SEC-J-12: `ProveedorDeLlavesKeyStoreTest` cubre 600 aceptado y 644/640/604 rechazados sin revelar la ruta.
- SEC-J-08.3: `AuditoriaLogbackTest` carga el `logback-spring.xml` real con Joran: archivo propio, solo esas líneas, anexado entre reinicios, nivel INFO fijo aunque la raíz sea ERROR.
- ARQ-29: regla ArchUnit sobre bytecode (con `allowEmptyShould`, hoy `registro` solo tiene `package-info`, así que la regla es preventiva) más comprobación sobre el código fuente que sí atrapa un import. Aceptable.
- ARQ-28: `layeredArchitecture` en ambos servicios más reglas explícitas; en consulta-publica la deuda `service.vista`/`CriterioBusquedaPublica` queda congelada y documentada. Nota: en ubicacion la capa Infrastructure solo se restringe con `mayNotBeAccessedByAnyLayer`, y el acceso Service→Infrastructure queda cubierto por `infrastructureNoDependeDeService` y el resto de reglas D43.
- SEC-J-04: `PaginaMapa.test.jsx` verifica `maxZoom` 8 con un punto, 10 con varios (`padding` incluido) y `referrerPolicy="no-referrer"` en la capa de teselas.

No añadí casos nuevos: no vi huecos relevantes. Observación (Bajo, no bloquea): las comprobaciones de keystore 600/644 dependen de POSIX; en un sistema de archivos sin POSIX la prueba de 644 no es significativa.

### No ejecutado

E2E contra servicios desplegados y k6 (D42, pospuesto por la usuaria): fuera del alcance de esta iteración.

### Veredicto Iteración 16: APROBADO

Los siete commits cumplen lo declarado y las pruebas nuevas son significativas. El único bug (BUG-J4, aislamiento de pruebas) estaba en los tests y quedó corregido; sin bugs abiertos de producción.


---

# Iteración 17 (2026-11-08)

Rama `feature/ubicacion-consulta-publica`. Alcance: `denyAll` y `@PreAuthorize` (D55), CI rojo (zona horaria y recifrado), integración del PR #7 (API Keys y límites) y E2E por el gateway con perfil `local`.

## Comandos usados

```bash
# Suites como en CI (ci.yml tiene 3 servicios: cientifico, consulta-publica y api-gateway), con TZ=UTC y luego TZ=America/Bogota
TZ=<zona> JAVA_TOOL_OPTIONS=-Duser.timezone=<zona> sg docker -c "mvn -B -ntp -pl <servicio> -am verify"
# Repetición x10
sg docker -c "mvn -B -ntp -q -pl servicio-cientifico -am test -Dtest='RecifradoServiceTest,BusquedaHallazgosApiTest' -Dsurefire.failIfNoSpecifiedTests=false"
mvn -B -ntp -q -pl servicio-consulta-publica -am test -Dtest=ValidadorDatosHallazgoTest -Dsurefire.failIfNoSpecifiedTests=false   # (esta clase vive en consulta-publica)
# E2E: MySQL locales del compose (docker compose up -d mysql-transaccional mysql-lectura mysql-gateway) y los 3 servicios con
SPRING_PROFILES_ACTIVE=local java -jar <modulo>/target/<modulo>-0.0.1-SNAPSHOT.jar   # cwd fuera del repo, variables del .env raíz exportadas
```

Detalles del E2E: los jars se ejecutaron con el directorio de trabajo fuera del repo para que no leyeran `servicio-cientifico/.env` (tiene configuración y llave de Aiven). `servicio-cientifico` usó una `UBICACION_CLAVES` aleatoria generada para la prueba. En los logs el perfil activo fue `local` en los tres y las URL JDBC fueron `localhost:3307` (científico), `localhost:3308` (consulta pública) y `localhost:3310` (gateway). Ningún log contiene "aivencloud". Los tokens vienen del client `biohub-pruebas` (password grant); el script lee la contraseña de `.env` sin imprimirla.

## Resultados de suites

| Servicio | TZ | Ejecutadas | Fallos | Errores | Omitidas | SeguridadEndpointsTest | ArquitecturaSeguridadTest | Resultado |
|---|---|---:|---:|---:|---:|---|---|---|
| servicio-cientifico | UTC | 322 | 0 | 0 | 2 | 56/56 | 2/2 | BUILD SUCCESS |
| servicio-cientifico | America/Bogota | 322 | 0 | 0 | 2 | 56/56 | 2/2 | BUILD SUCCESS |
| servicio-consulta-publica | UTC | 239 | 0 | 0 | 0 | 40/40 | 2/2 | BUILD SUCCESS |
| servicio-consulta-publica | America/Bogota | 239 | 0 | 0 | 0 | 40/40 | 2/2 | BUILD SUCCESS |
| api-gateway | UTC | 168 | 0 | 0 | 0 | n/a | n/a | BUILD SUCCESS |
| api-gateway | America/Bogota | 168 | 0 | 0 | 0 | n/a | n/a | BUILD SUCCESS |

Las 2 omitidas de científico son `PublicadorHallazgoE2ETest` y `PublicadorBrokerDesplegadoTest` (opt-in con `QA_RMQ_*`, igual que en la iteración 16).

Repeticiones (10 ejecuciones consecutivas cada una, todas con exit 0):

| Prueba | Casos por ejecución | Zona | Fallos en 10 ejecuciones |
|---|---:|---|---:|
| `RecifradoServiceTest` | 6 | UTC | 0 |
| `BusquedaHallazgosApiTest` | 21 | UTC | 0 |
| `ValidadorDatosHallazgoTest` | 20 | UTC | 0 |
| `ValidadorDatosHallazgoTest` | 20 | America/Bogota | 0 |

## E2E por el gateway (puerto 8080, perfil `local`)

Quién respondió se dedujo del cuerpo y las cabeceras: el 403 del gateway es `application/problem+json;charset=UTF-8` (solo ocurre con `sinrol1`, que no tiene ninguno de los 5 roles); el 403 del servicio es `application/problem+json` sin `charset`. El 401 sin token lo da el gateway (sin cuerpo).

| Método | Ruta | Usuario | Esperado | Obtenido | |
|---|---|---|---|---|---|
| GET | /api/cientifico/ubicacion/hallazgos | cientifico1, investigador1, auditor1, analista1, admin1 | 200 | 200 (x5) | ✅ |
| GET | /api/cientifico/ubicacion/hallazgos | sinrol1 | 403 | 403 problem+json `ROL_INSUFICIENTE` (gateway) | ✅ |
| GET | /api/cientifico/ubicacion/hallazgos | sin token | 401 | 401 | ✅ |
| GET | /api/cientifico/ubicacion/mapa | cientifico1 | 200 | 200 | ✅ |
| GET | /api/cientifico/ubicacion/mapa | admin1, investigador1, auditor1, analista1 | 403 | 403 problem+json `ROL_INSUFICIENTE` (servicio) x4 | ✅ |
| GET | /api/cientifico/ubicacion/mapa | sinrol1 | 403 | 403 problem+json `ROL_INSUFICIENTE` (gateway) | ✅ |
| GET | /api/cientifico/ubicacion/mapa | sin token | 401 | 401 | ✅ |
| POST | /api/cientifico/ubicacion/recifrado | admin1 | 2xx | 200 `{claveActiva:v1, revisados:0, recifrados:0, fallidos:0}` | ✅ |
| POST | /api/cientifico/ubicacion/recifrado | cientifico1, investigador1 | 403 | 403 problem+json (servicio) | ✅ |
| POST | /api/cientifico/ubicacion/recifrado | sinrol1 | 403 | 403 problem+json (gateway) | ✅ |
| POST | /api/cientifico/ubicacion/recifrado | sin token | 401 | 401 | ✅ |
| GET | /api/cientifico/ping | los 5 roles | 200 | 200 (x5) | ✅ |
| GET | /api/cientifico/ping | sinrol1 | 403 | 403 `ROL_INSUFICIENTE` (gateway) | ✅ |
| GET | /api/cientifico/ping | sin token | 401 | 401 | ✅ |
| GET | /api/publica/fichas | sin token | 200 | 200 | ✅ |
| GET | /api/publica/fichas/00000000-0000-0000-0000-000000000000 | sin token | 404 | 404 problem+json "La ficha solicitada no existe." | ✅ |
| GET | /api/publica/ping | sin token | 200 | 200 | ✅ |
| GET | /api/publica/eventos/recibidos | admin1 | 200 | 200 | ✅ |
| GET | /api/publica/eventos/recibidos | cientifico1, investigador1, sinrol1 | 403 | 403 (servicio, cuerpo vacío; ver OBS-J17-1) | ✅ |
| GET | /api/publica/eventos/recibidos | sin token | 401 | 401 (servicio, cuerpo vacío) | ✅ |
| POST | /api/publica/fichas | sin token / admin1 | rechazada | 401 / 403 `ROL_INSUFICIENTE` (gateway) | ✅ |
| GET | /api/cualquiercosa | admin1 / sin token | rechazada | 403 `ROL_INSUFICIENTE` / 401 | ✅ |
| GET | /api/gateway/consumidores | cientifico1 / sin token | 403 / 401 | 403 / 401 | ✅ |
| POST | /api/gateway/consumidores | admin1 | 201 | 201 | ✅ |
| POST | /api/gateway/consumidores/{id}/keys | admin1 | 201 | 201 (apiKey mostrada solo aquí) | ✅ |
| GET | /api/v1/externa/fichas | X-API-Key válida | 200 | 200 con `X-RateLimit-Limit: 60` y `X-RateLimit-Remaining: 58` | ✅ |
| GET | /api/v1/externa/fichas | sin key | 401 | 401 `API Key inválida` | ✅ |
| GET | /api/v1/externa/fichas | key inválida | 401 | 401 | ✅ |
| GET | /api/v1/externa/fichas | JWT de admin1 sin key | 401 | 401 | ✅ |
| POST | /api/v1/externa/fichas | con key válida | rechazada | 401 (la ruta externa es solo GET) | ✅ |
| DELETE | /api/gateway/keys/{id} | admin1 | 204 | 204 | ✅ |
| GET | /api/v1/externa/fichas | key revocada | 401 | 401 (revocación inmediata) | ✅ |
| GET | /api/publica/ping (x112) | sin token, misma IP | 429 + Retry-After | 429 en la petición 112 (había ~8 previas: 120 por minuto), `Retry-After: 58`, problem+json `LIMITE_EXCEDIDO` (gateway) | ✅ |

Total: 50 comprobaciones del script principal, 50 aprobadas, 0 fallidas. Las llamadas internas con JWT no pidieron API Key (PR #7 no rompe lo anterior). La key de prueba se revocó y el consumidor `qa-j17-*` quedó creado en la BD local (no hay endpoint para borrarlo).

Comprobaciones adicionales: mientras el cupo por IP estaba agotado, `/api/publica/eventos/recibidos` con y sin JWT también devolvió 429 (el límite se aplica antes de la autenticación; es coherente con el diseño). Llamadas directas a los puertos de los servicios confirman que el 403 de `eventos/recibidos` lo emite `servicio-consulta-publica` con cuerpo vacío.

## Bugs

No se encontraron bugs bloqueantes. Observaciones:

### OBS-J17-1 (Bajo, no bloquea) — responsable sugerido: backend (consulta-publica)
- **Qué:** el 403 y el 401 de `GET /api/publica/eventos/recibidos` salen del propio servicio con cuerpo vacío y `Content-Type: application/octet-stream` (solo la cabecera `WWW-Authenticate`). El 403 de `ubicacion` en científico sí es problem+json `ROL_INSUFICIENTE` (D55).
- **Reproducir:** `GET /api/publica/eventos/recibidos` con token de `cientifico1` por el gateway, o directo a `localhost:8083`.
- **Esperado vs obtenido:** el contrato (§ eventos/recibidos) solo exige 401/403, así que no incumple; pero es inconsistente con el formato de error del resto (§5.1 y D55). Obtenido: 403 con cuerpo vacío.
- **Archivo probable:** `servicio-consulta-publica/.../config/SecurityConfig` (falta un `AccessDeniedHandler`/`AuthenticationEntryPoint` que emita problem+json).

### OBS-J17-2 (Informativo)
`/api/publica/**` no devuelve cabeceras `X-RateLimit-*` en respuestas 2xx (solo `Retry-After` en el 429). Es el comportamiento del código (`ApiKeyRateLimitFilter.atenderPortal`) y del contrato (`X-RateLimit-*` solo en la API externa), así que no es defecto; se anota porque el encargo esperaba cabeceras de límite en la ruta pública.

### OBS-J17-3 (Informativo)
Las pruebas de integración de `servicio-cientifico` leen `servicio-cientifico/.env` (cwd del módulo), que hoy contiene credenciales y llave reales de Aiven. No hubo conexión a Aiven (0 coincidencias de "aivencloud" en los logs de las suites), pero conviene que 17g lo revise.

## No ejecutado

- `web-publica` (npm test, build y Playwright del job `frontend` de CI): fuera del alcance de la tarea 17f.
- Pruebas opt-in contra el broker desplegado (`QA_RMQ_*`): omitidas por diseño (2).
- 429 de la API externa por consumidor (60/min) y por 20 keys inválidas/min: no probados en E2E; los cubre la suite del gateway.
- `servicio-equipos` y `servicio-reportes` no se arrancaron (fuera de alcance).

## Veredicto Iteración 17: APROBADO

Las tres suites de CI pasan en UTC y en America/Bogota (322, 239 y 168 pruebas, 0 fallos), las pruebas antes inestables (`RecifradoServiceTest`, `BusquedaHallazgosApiTest`, `ValidadorDatosHallazgoTest`) pasaron 10 de 10 veces, y los 50 casos E2E por el gateway coinciden con la matriz de `11-seguridad-y-acceso.md`, el 403 de `sinrol1` es problem+json `ROL_INSUFICIENTE` (D55) y las API Keys y el límite por IP funcionan sin romper las rutas internas con JWT. Solo hay una observación de formato de error (Bajo).

---

# Iteración 18: verificación de SEC-J-19 y SEC-J-20

Fecha: 2026-11-09. Rama `feature/ubicacion-consulta-publica`. Commits verificados: SEC-J-19 (`ArquitecturaSeguridadTest` sin vías de elusión) y SEC-J-20 (`ProblemaSeguridadHandler`: 403 problem+json `ROL_INSUFICIENTE` y 401 del contrato en todo el servicio). Durante la sesión el historial se reescribió: los hashes actuales son `fc769a9` (SEC-J-19) y `efed795` (SEC-J-20); el contenido es el mismo que los `d718be5` y `e49373a` del encargo.

## Resumen de ejecución (`mvn -B -pl servicio-cientifico,servicio-consulta-publica -am verify`)

| Ejecución | servicio-cientifico | servicio-consulta-publica | Resultado |
|---|---|---|---|
| TZ=UTC | 434 pruebas, 0 fallos, 2 omitidas | 339, 0 fallos, 0 omitidas | BUILD SUCCESS |
| TZ=America/Bogota (1.ª) | 434, 0, 2 omitidas | 339, **1 fallo**, 0 | BUILD FAILURE (ver BUG-J18-1) |
| TZ=America/Bogota (repetición completa) | 434, 0, 2 omitidas | 339, 0, 0 | BUILD SUCCESS |

- La suma de los XML de `surefire-reports` de científico da 437 (2 omitidas), como midió el Tech Lead; el resumen de Maven da 434. Es una diferencia de conteo del informe (probablemente pruebas dinámicas/anidadas), no un fallo; las 2 omitidas coinciden (broker desplegado opt-in).
- Repetí 5 veces `PublicadorNoPermitidoIntegracionTest` aislada en Bogota: 5/5 en verde. El fallo es intermitente (1 de 7 ejecuciones en total).
- Cobertura (JaCoCo): no disponible en este build, no medida.

## Cobertura de las pruebas nuevas (revisadas leyendo el código y ejecutadas)

| Requisito | Dónde se prueba | Estado |
|---|---|---|
| 403 problem+json `ROL_INSUFICIENTE`, `Cache-Control: no-store`, cuerpo fijo (`type`, `title`, `status`, `detail`, `instance`, `codigo`) fuera de `ubicacion` | científico: `SeguridadEndpointsTest` (todo endpoint con JWT sin roles y con rol no permitido, `/api/cientifico/ping`, `/api/cientifico/eventos/prueba`, denyAll en `/`, `/actuator/*`, `/api/publica/*`...). consulta-pública: `/api/publica/eventos/recibidos` con 6 roles y sin roles, y rutas denyAll | Cubierto |
| 401 sin cuerpo, `WWW-Authenticate: Bearer`, `no-store` | ambos `SeguridadEndpointsTest` (sin token en todos los endpoints; ruta pública con token malo) | Cubierto |
| 401 sin la descripción interna del decodificador JWT | `unJwtInvalidoDa401SinCuerpoSinFiltrarElMotivoInterno` en ambos: cabecera exacta `Bearer error="invalid_token"`, sin "firma rota" ni "interno-7" | Cubierto |
| `UbicacionExceptionHandler` sigue respondiendo y auditando primero en `ubicacion` | Sin prueba nueva en SEC-J-20; lo cubren las existentes `MapaApiTest.losDemasRolesRecibenElMapaDenegado` (problem+json, no-store, `ROL_INSUFICIENTE`) y `AuditoriaYSinFiltracionesTest` / `BusquedaHallazgosApiTest` (auditoría del 403); todas en verde | Cubierto (regresión) |
| `ConsultaPublicaExceptionHandler` ya no convierte AccessDenied en 500 | `unAccessDeniedExceptionDeUnEndpointDeFichasNoSeConvierteEn500` (mock lanza `AccessDeniedException`, esperado 403 `ROL_INSUFICIENTE` sin filtrar el mensaje) | Cubierto |
| El 403 no filtra trazas ni detalles internos | `elCuerpoDel403NoFiltraTrazasNiDetallesInternos` en ambos | Cubierto |

Huecos menores (no bloqueantes): no hay prueba que combine "denyAll de la cadena de filtros" con auditoría (no se audita fuera de `ubicacion`, lo que es el diseño); el 403 de `/actuator/env` se probó en vivo (abajo) y en la suite, pero la suite no comprueba `Cache-Control` en esa ruta concreta salvo mediante `esRolInsuficiente`, que sí lo hace.

## Prueba de mutación manual

| Mutación (temporal) | Resultado |
|---|---|
| Quitar `@PreAuthorize` de `PingController` (científico, línea 21) | `ArquitecturaSeguridadTest` falla (69 pruebas, 1 fallo): "methods ... annotated with @RequestMapping should tener @PreAuthorize con un rol..." |
| Cambiar `permitAll()` por `isAuthenticated()` en `FichasPublicasController` línea 44 (consulta-pública) | `ArquitecturaSeguridadTest` falla (66 pruebas, 1 fallo) |

Ambos archivos restaurados con `git checkout -- <archivo>`; `git status` limpio tras cada mutación y al final.

## E2E por el gateway (perfil `local`, nunca `aiven`)

Infraestructura local arriba (keycloak, mysql, rabbitmq, redis). Levanté consulta-pública (8083), científico (8081) y api-gateway (8080) con perfil `local`, cwd fuera del repo y llave de ubicación aleatoria. Usuario `sinrol1` (sin ninguno de los 5 roles).

| Petición | Resultado obtenido | Estado |
|---|---|---|
| GET `/api/publica/eventos/recibidos` por gateway, `sinrol1` | 403, `application/problem+json`, `no-store`, `{"codigo":"ROL_INSUFICIENTE", "instance":"/api/publica/eventos/recibidos", ...}` | OK |
| Ídem sin token (gateway) | 401, `WWW-Authenticate: Bearer`, `no-store`, sin cuerpo | OK |
| Ídem directo al servicio 8083, `sinrol1` | 403 problem+json `ROL_INSUFICIENTE`, `no-store` (antes de SEC-J-20: cuerpo vacío) | OK, es la causa raíz de OBS-J17-1 corregida |
| Ídem directo 8083, sin token | 401 `WWW-Authenticate: Bearer`, `no-store`, sin cuerpo | OK |
| Ídem directo 8083, token basura | 401 `WWW-Authenticate: Bearer error="invalid_token"`, sin descripción interna | OK |
| Directo 8081 `/api/cientifico/ping`, `sinrol1` | 403 problem+json `ROL_INSUFICIENTE`, `no-store` | OK |
| Directo 8081 `/api/cientifico/ubicacion/mapa`, `sinrol1` | 403 problem+json (handler de `ubicacion`) | OK |
| Directo 8081 `/api/cientifico/ping` sin token | 401 `Bearer`, `no-store`, sin cuerpo | OK |
| Directo 8081 `/actuator/env`, `sinrol1` | 403 problem+json `ROL_INSUFICIENTE` | OK |

OBS-J17-1: **CERRADO**. Servicios y gateway detenidos al terminar; sin cambios en el árbol.

## Bugs

### BUG-J18-1 (Bajo) Prueba intermitente: `PublicadorNoPermitidoIntegracionTest.elBrokerRechazaUnUserIdFalsificado`
- Responsable sugerido: backend (dueño de la prueba, no es código de producción).
- Archivo: `servicio-consulta-publica/src/test/java/co/edu/unbosque/biohub/consultapublica/eventos/PublicadorNoPermitidoIntegracionTest.java:86`.
- Reproducir: `TZ=America/Bogota mvn -B -pl servicio-cientifico,servicio-consulta-publica -am verify` repetidas veces; falló 1 vez en 7 (no falló en UTC ni en 5 ejecuciones aisladas).
- Esperado: `assertThatThrownBy` recibe una excepción porque RabbitMQ rechaza el `user_id` falsificado.
- Obtenido: `Expecting code to raise a throwable` (línea 86). El log de esa misma ejecución muestra que el broker sí rechazó: `PRECONDITION_FAILED - user_id property set to 'guest' but authenticated user was 'intruso_mq2'`, pero el cierre de canal llega de forma asíncrona y el `queueDeclarePassive` posterior se ejecutó antes de que se propagara (condición de carrera). No es una regresión de SEC-J-19/20 (ningún archivo tocado por esos commits interviene); no depende de la zona horaria, solo coincidió.
- Sugerencia: esperar con `Awaitility` hasta ver el error de canal (o comprobar la ausencia de la ficha y que el mensaje no se entregó) en vez de una sola operación posterior.

## No ejecutado

- Frontends (`web-interna`, `web-publica`), servicio-equipos y servicio-reportes: fuera de alcance.
- Cobertura JaCoCo: no configurada.

## Veredicto Iteración 18: APROBADO

SEC-J-19 y SEC-J-20 cumplen lo declarado: las mutaciones sobre `@PreAuthorize` son detectadas, el 403 problem+json `ROL_INSUFICIENTE` con `no-store` aplica a todo el servicio (comprobado en pruebas y en vivo), el 401 conserva el contrato sin filtrar la descripción del decodificador, el handler de `ubicacion` sigue auditando y `ConsultaPublicaExceptionHandler` ya no produce 500. Las suites pasan en UTC y Bogota (434 y 339, 0 fallos tras repetir). Queda un único hallazgo Bajo (BUG-J18-1, prueba intermitente), que no bloquea el push pero conviene estabilizar porque puede hacer fallar el CI al azar.

---

# Iteración 20 – teselas por el gateway (20b)

Alcance: parte frontend de SEC-J-04 (cambios sin commit en `web-interna/src/ubicacion/**`: `CapaTeselasGateway.js`, `TeselasGateway.jsx`, `MapaHallazgos.jsx`, `PaginaMapa.jsx`, `ubicacionApi.js` con `getBlob`, y las pruebas asociadas).

## Comandos y resultados

| Comando (en `web-interna`) | Resultado |
|---|---|
| `npx vitest run` (estado recibido, sin mis pruebas) | 5 archivos, **73 pruebas aprobadas, 0 fallidas** |
| `npx vitest run` (con `qa-teselas.test.jsx`) | 6 archivos, **84 pruebas aprobadas, 0 fallidas** |
| `npm run build` | OK, 21 módulos, `index-*.js` 248.30 kB (77.42 kB gzip) |
| Cobertura | No configurada |

Alcance del cambio: `git status` fuera de `web-interna/src/ubicacion/**` solo muestra `docs/equipo/00-plan.md`, `04`, `11` (y `06`, mío). No se tocaron `App.jsx`, `package.json`, login ni navegación. No hay dependencias nuevas. Única mención a OSM en código: la atribución (obligatoria) y comentarios.

## Qué se comprobó

- Las pruebas del desarrollador sí prueban lo pedido: URL relativa `/api/gateway/teselas/{z}/{x}/{y}.png`, con el cliente real `fetch` a `apiUrl` + ruta con `Authorization: Bearer`, `Accept: image/png`, `credentials: omit` y sin `cache` (`ubicacionApi.test.js`, `CapaTeselasGateway.test.jsx`); `fetch` nunca recibe `openstreetmap` y no hay `<img src>` remoto; `getBlob` 200/400/401/403/429/502/503/sin red/corte de cuerpo/abort/fallo de token; aborto por tesela, por zoom y al desmontar sin `tileerror`; revocación de object URL (carga, error de decodificación, descarte, idempotente); 401 una sola vez; 403/429/5xx/0 no cierran sesión; cliente sin `getBlob` degrada a `tileerror`; umbral y paso a Lista en `PaginaMapa.test.jsx` (con `TeselasGateway` simulado).
- No se rompió nada existente: las 73 pruebas previas pasan; `alt=''` y `role=presentation` en teselas; marcadores y teclado siguen cubiertos en `PaginaMapa.test.jsx`/`MapaHallazgos` sin cambios de comportamiento (el diff de `MapaHallazgos.jsx` solo cambia la capa y el contador).
- Huecos que cubrí en `web-interna/src/ubicacion/pruebas/qa-teselas.test.jsx` (11 pruebas, todas pasan, con Leaflet real en jsdom): respuesta (blob) tardía tras desmontar con cliente que ignora la señal (sin object URL ni errores); 401 tardío tras desmontar (no cierra sesión); StrictMode (una sola capa, descargas del primer montaje abortadas, sin errores); cambio de `cliente` entre renders (capa recreada, señales anteriores abortadas); 429 repetidos superan `MAX_ERRORES_TESELAS` y avisan una sola vez; el umbral exacto no avisa; abortos no cuentan; sin `<img>` externos; 401 con aborto previo no avisa.
- `06-plan-pruebas.md`: agregados WC-09..WC-14 y WI-15 (sin tocar lo existente) y la fila de trazabilidad de HU-07.2.

## Bugs y observaciones

### BUG-J20-1 (Bajo) `alSesionVencida` se llama una vez por capa, no una vez por mapa
- Responsable sugerido: frontend.
- Archivo: `web-interna/src/ubicacion/componentes/CapaTeselasGateway.js:24,55-58` (la bandera `_sesionAvisada` vive en la capa) y `TeselasGateway.jsx:17-29` (el efecto recrea la capa si cambia `cliente`, `attribution` o `maxZoom`).
- Pasos: montar `TeselasGateway` con un cliente que responde 401, esperar, `rerender` con otro cliente que también da 401.
- Esperado (README/11 §7: "una sola vez"): 1 llamada. Obtenido: 2 (`qa-teselas.test.jsx`, prueba "cambio de cliente... una vez POR CAPA", que documenta el comportamiento actual y pasa). Además, un 401 de la lista (`PaginaMapa.jsx:52`) y otro de teselas pueden llamarlo ambos. En la práctica `alSesionVencida` de Santiago redirige al login, por lo que el impacto es bajo; conviene que sea idempotente.
- Corrección sugerida: mover la bandera a `TeselasGateway` (`useRef`) o dejar documentado que el consumidor debe ser idempotente.

### BUG-J20-2 (Bajo, documentación) Casos de prueba existentes con texto obsoleto
- Responsable: QA/docs. `06-plan-pruebas.md` WI-05 dice "teselas solo de OSM" y WP-09 "ninguna a teselas": ahora las teselas salen por el gateway del mismo origen. No los reescribí (regla); WC-09 y WI-15 describen el comportamiento vigente.

### Observaciones (no son bugs del cambio)
- El mapa base no reintenta: más de 3 teselas fallidas (incluido un 429 pasajero o un 503 del circuit breaker) pasan a la vista Lista hasta recargar, y el contador no se reinicia. Es el diseño previo; el riesgo de 429 es bajo (una vista usa 20-40 de 600/min), pero un `Retry-After` podría usarse.
- No verificado en navegador real (WI-15 pendiente): carga de `blob:` con una CSP, comportamiento real de Leaflet al hacer zoom animado con descargas en vuelo y que el gateway esté levantado. Las pruebas usan jsdom con medidas simuladas.
- La capa depende de internos de Leaflet (`_tiles`, `_tileZoom`, `_abortLoading`); está fijada la versión 1.9, pero una actualización mayor exige revisar `qa-teselas.test.jsx` y la prueba de zoom.

## Veredicto Iteración 20b: APROBADO CON OBSERVACIONES

El cambio cumple lo declarado: las teselas van solo al gateway con Bearer a través del `cliente` inyectado, cero peticiones a OSM, abortos y object URL bien manejados, aviso y paso a Lista intactos, sin regresiones (84/84 pruebas, build OK) y sin salirse del reparto. Quedan dos hallazgos Bajos (BUG-J20-1, BUG-J20-2) y la verificación en navegador real pendiente (WI-15), que no bloquean el commit.

---

## Iteración 20f – verificación de SEC-J-24 (zoom ≥ 9 sin caché)

Alcance: cambios sin commit de la tarea 20e (`ubicacionApi.js`: `getBlob(ruta, { signal, sinCache })` y orden de `...opciones`; `CapaTeselasGateway.js`: `ZOOM_SIN_CACHE = 9`; pruebas, README, `11` §7, D57).

### Comandos y resultados

| Comando (en `web-interna`) | Resultado |
|---|---|
| `npx vitest run` (estado recibido) | 6 archivos, **86 pruebas aprobadas, 0 fallidas** (coincide con el Tech Lead; antes 84) |
| `npm run build` | OK, 21 módulos, `index-*.js` 248.30 kB (77.42 kB gzip) |
| `npx vitest run` (con mis 5 casos nuevos en `qa-teselas.test.jsx`) | 6 archivos, **91 pruebas aprobadas, 0 fallidas** |
| Cobertura | No configurada |

### Qué se comprobó

- Umbral: la prueba nueva de `CapaTeselasGateway.test.jsx` cubre zooms 0, 5, 8 (`sinCache: false`) y 9, 10, 12 (`true`) y la constante `ZOOM_SIN_CACHE === 9`. Suficiente.
- `ubicacionApi.test.js`: con `sinCache` el `fetch` lleva `cache: 'no-store'`, `Authorization: Bearer`, `credentials: 'omit'` y la misma `signal`; sin la opción no existe la propiedad `cache`. Suficiente.
- Regresión por mover `...opciones`: `get` sigue con `cache: 'no-store'` (prueba existente `envía Bearer, no-store...` pasa y mi prueba nueva lo reafirma con headers exactos y `omit`). Con `opciones` primero ya nada puede sobrescribir `headers`, `credentials` ni `signal`; hoy `opciones` solo trae `cache`, así que no hay cambio observable. Las 11 pruebas previas de `qa-teselas.test.jsx` siguen pasando.
- Huecos cubiertos por mí en `web-interna/src/ubicacion/pruebas/qa-teselas.test.jsx` (5 pruebas nuevas, todas pasan): mapa Leaflet real + cliente real con `fetch` simulado a zoom 8, 9 y 12 (cada petición: URL `http://gw/api/gateway/teselas/{z}/x/y.png`, `no-store` solo desde 9, Bearer, `Accept: image/png`, `omit` y `AbortSignal`); `sinCache: false` y `undefined` explícitos no mandan `cache`; `get` conserva `no-store` y headers exactos; con `sinCache` un 401 sigue traduciéndose a `ErrorApi` 401. Los tests del desarrollador probaban capa y cliente por separado; estos prueban la cadena completa.
- Alcance: sin dependencias nuevas, sin cambios fuera de `web-interna/src/ubicacion/**` y docs. No toqué código de producción.

### Bugs

Ninguno nuevo (no se abre BUG-J20-3 ni posteriores). Siguen abiertos BUG-J20-1 y BUG-J20-2 de la 20b, sin relación con este cambio.

### Observaciones (no bloquean)

- `no-store` en la petición evita leer y guardar, pero NO borra entradas de zoom ≥ 9 que ya estén en disco de versiones anteriores (hasta 24 h por el `max-age=86400`). Es transitorio; el `Clear-Site-Data: "cache"` al cerrar sesión (opcional, Santiago) lo resolvería.
- El umbral usa `coords.z` de Leaflet; con `zoomOffset` o capas retina distintos de 0 habría que revisarlo (hoy no se usan).
- La no escritura real en la caché HTTP solo se puede comprobar en navegador real (pestaña Network / `chrome://cache`): las pruebas verifican la opción enviada a `fetch`, no el comportamiento del navegador. Pendiente manual junto con WI-15.

### Veredicto 20f: APROBADO CON OBSERVACIONES

SEC-J-24 queda corregido en el código: el umbral 9, la opción `no-store` y la conservación de token, `omit` y señal están cubiertos con pruebas, `get` no cambió y no hay regresiones (86/86 recibido, 91/91 con mis casos, build OK). Las observaciones son de verificación en navegador real y de entradas antiguas en caché.

---

# Iteración 21 – arnés, E2E de ubicación y CI de web-interna (21c)

Alcance: cambios sin commit de 21a (arnés `src/ubicacion/dev/`, E2E `src/ubicacion/e2e/*.e2e.js`, `playwright.config.js`, `package.json`/lock, correcciones de `MapaHallazgos.jsx`, README) y 21b (`.github/workflows/ci.yml`). No toqué código de producción, pruebas existentes, `ci.yml` ni `package.json`; las mutaciones se aplicaron con copia de respaldo y se restauraron (verificado con `sha256sum`: los cuatro archivos tocados quedaron idénticos). Mis pruebas de apoyo están fuera del repo (scratchpad de la sesión).

## Comandos ejecutados y resultados

| Comando | Resultado |
|---|---|
| `cd web-interna && npx vitest run` | 6 archivos, **92 pruebas aprobadas, 0 fallidas** (coincide con el reporte del agente) |
| `npm run build` | OK, 21 módulos; `dist/` = `index.html`, `assets/index-*.css` (0.65 kB), `assets/index-fygnyAIi.js` (248.30 kB, 77.42 kB gzip: mismo tamaño que en 20b/20f). `grep -ril "arnes\|simulado\|token-simulado\|sesionSimulada\|Playwright\|__llamadasFetch\|localhost:5175" dist` sin resultados: **nada del arnés en `dist/`** |
| `E2E_SIN_WEBKIT=1 npm run test:e2e` (3 corridas seguidas, local, Chromium + Firefox) | **78 aprobadas, 0 fallidas, 0 inestables** en las 3 (35.4 s, 35.4 s, 34.9 s). 39 por motor |
| `E2E_SIN_WEBKIT=1 npx playwright test --repeat-each=3 --workers=8` | **234 aprobadas, 0 fallidas** (1.6 min) |
| Igual con carga: 4 procesos `yes` ocupando CPU, `--repeat-each=2 --workers=16` | **156 aprobadas, 0 fallidas** (1.6 min) |
| Docker `mcr.microsoft.com/playwright:v1.64.1-noble`, `CI=1` (retries=1, reporter html), 3 motores, 2 corridas | **117/117 aprobadas en las dos** (47.3 s y 56.0 s); sin línea de "inestables" (`retries` no se usó). WebKit corre en la imagen |
| `npm audit --omit=dev --audit-level=high` | 0 vulnerabilidades |
| `TZ=America/Bogota mvn -B -ntp -pl servicio-consulta-publica -am test -DargLine=-Duser.timezone=America/Bogota` | BUILD SUCCESS, **364 pruebas, 0 fallos, 0 errores**; el XML de Surefire muestra `user.timezone=America/Bogota` (35 archivos). `ValidadorDatosHallazgoTest`: 20 pruebas |
| Igual con `TZ=UTC` y `-DargLine=-Duser.timezone=UTC` | 364 / 0 / 0; `user.timezone=UTC` |
| Humo del modo real del arnés (no es WI-13) | `http://localhost:5173/src/ubicacion/dev/arnes.html?pantalla=mapa` redirige al Keycloak de Compose y muestra el formulario "Sign in to BioHub". En el puerto 5175 Keycloak responde "Parámetro no válido: redirect_uri" (solo acepta 5173, como dice el README). No inicié sesión: queda pendiente |
| Cobertura | No configurada |

No se ejecutó: el job de GitHub Actions (no hay forma local; revisado por lectura y con `yaml.safe_load`, que lista los 4 jobs y las 5 entradas de la matriz), el modo real con login (WI-13) y la guía manual de caché (WI-15 manual), WebKit en el equipo (faltan librerías del sistema; sí en Docker).

Incidente propio, sin efecto en el código: `npm ci --dry-run` **borró `web-interna/node_modules`** (comportamiento conocido de npm). Lo restauré con `npm ci` y repetí Vitest (92/92). `git status` no cambió. No usar `--dry-run` para "ver" si el lockfile es consistente.

Inestabilidad observada: **ninguna** en 78+78+234+156 ejecuciones locales y 234 en Docker, con y sin carga. Las E2E esperan por condiciones (`expect`, `expect.poll`); quedan 4 `waitForTimeout`: `mapa.e2e.js:78` y `:209` (esperas negativas, "no debe aparecer ningún error"), `mapa.e2e.js:201` y `seguridad.e2e.js:12` (400 ms entre clics del zoom). Los dos últimos están en un bucle que termina por el estado del botón ("+" deshabilitado), así que una máquina lenta alarga la prueba pero no la rompe; el helper está duplicado en los dos archivos.

## Mutaciones (código de producción, temporales; Vitest y Playwright Chromium, 39 pruebas)

| # | Mutación | Vitest | E2E (Chromium) | Veredicto |
|---|---|---|---|---|
| M1 | `keydown:` -> `keydownX:` en `MapaHallazgos.jsx` (quita el Enter del marcador) | 1 falla (la nueva de Enter/Espacio) | 1 falla: WI-05 teclado | Detectada por las dos capas |
| M2 | `ZOOM_SIN_CACHE` 9 -> 10 | 2 fallan | 1 falla: WI-15 (`no-store` a zoom 9) | Detectada |
| M2b | `ZOOM_SIN_CACHE` 9 -> 8 | 2 fallan | 1 falla: WI-15 (zoom 8 no debe llevar `cache`) | Detectada |
| M3 | `TeselasGateway.jsx` usa `L.tileLayer("https://tile.openstreetmap.org/...")` en vez de la capa del gateway | 8+ fallan | **7 fallan**: WI-15 (los dos), WI-14, WC-12, WC-13 (los 3 casos) | Detectada |
| M5 | `ZOOM_MAXIMO` 12 -> 13 | 1 falla | 2 fallan: WI-14 y WI-15 | Detectada |
| M6 | `esAltoRango()` devuelve siempre `true` | 8+ fallan | **9 fallan**: WI-02 (4 roles) y WI-06 (4 roles + sin rol) | Detectada |
| M8 | `console.log(JSON.stringify(puntos))` en el mapa | 0 fallan | 1 falla: WI-10 (consola) | Detectada solo por la E2E (aporta valor sobre Vitest) |
| M4 | Quitar `<CerrarZoomAlQuitar />` (volver al error `_leaflet_pos`) | 0 fallan | **0 fallan en la corrida completa** | **Detectada solo a veces**: ver abajo y BUG-J21-1 |
| M9 | Quitar `!this._sesionAvisada` (un `alSesionVencida` por tesela) | 3 fallan | **0 fallan** | **No detectada por la E2E** (BUG-J21-2); sí por Vitest |
| M10 | Quitar `' '` de `esTeclaDeActivacion` | 1 falla (Espacio) | no aplica (la E2E solo prueba Enter) | Detectada por Vitest |
| M11 | Quitar `e.originalEvent.preventDefault()` del `keydown` | 0 fallan | 0 fallan | **No detectada** (BUG-J21-3) |

Detalle de M4: la prueba "Saltar el mapa y ver la lista" de `mapa.e2e.js` sí reproduce el error, pero a veces. Con la corrección quitada y solo esa prueba con `--repeat-each=6` (Chromium y Firefox): **8 fallos y 4 aciertos de 12** (el `pageerror` `Cannot read properties of undefined (reading '_leaflet_pos')`, en Firefox `can't access property "_leaflet_pos", el is undefined`). En la corrida completa pasó. Con la corrección puesta, esa misma prueba pasó en todas las ejecuciones (cientos). Con una prueba mía que pulsa "Lista" a 0, 10, 30, 60, 100, 150 y 200 ms después de que aparecen los marcadores (3 repeticiones, 2 motores): sin la corrección falla en **25 de 42**, con la corrección en **0 de 42**.

## Calidad de las E2E, caso por caso

- **Fuertes** (comprueban lo que el caso pide; ver mutaciones): WI-02, WI-05 (marcadores, ratón y Enter/Esc con foco), WI-06 (0 peticiones al gateway medidas en la red, no solo en el mock), WI-10 (URL, título, almacenamiento, cookies, consola), WI-12, WI-14, WI-15 (red completa sin OSM ni hosts externos, Bearer, `fetch` en vez de `<img>`, `no-store` solo desde zoom 9, 429/503/sin red sin respaldo a OSM) y WC-13.
- **Suficientes**: WI-01, WI-03, WI-07, WI-08 y WI-09 (afirman textos y la petición con filtros/`hallazgoId`).
- **Débiles**: WI-04 y WC-12 (BUG-J21-2) y la parte "Lista durante el zoom" (BUG-J21-1).
- **Límites que el propio README declara y confirmo**: las cabeceras `Cache-Control`/`Pragma` de las teselas solo se registran como anotación (Playwright desactiva la caché con rutas interceptadas), así que "no queda en disco" no es verificable en E2E y sigue en la guía manual. El WI-04 de las E2E prueba que la UI pide iniciar sesión, no la redirección real de Keycloak (eso es WI-13).
- **Puertos**: 5175 fijo con `--strictPort` y `reuseExistingServer: !CI`. En CI no hay reutilización y falla ruidosamente si el puerto está ocupado; en local, un servidor ajeno en 5175 se reutilizaría (con `.env.development` apuntando a 8080 no rompe). 8080 no es un puerto que se use: es solo el origen interceptado (`page.route`); con un gateway real escuchando en 8080 las pruebas siguen sin tocarlo.
- La constante `ZOOM_SIN_CACHE = 9` está copiada en `seguridad.e2e.js:3`: es deliberada, así M2 y M2b se detectan.

## Las dos correcciones de `MapaHallazgos.jsx`

- **Enter/Espacio (`keydown`)**: correcta. Leaflet 1.9.4 no convierte `keypress` en `click` (`Map.js` solo reenvía el evento), por eso Enter no abría el detalle; M1 lo demuestra. Verificado con una prueba mía en Chromium y Firefox: Espacio sobre el marcador abre el detalle y llega con `defaultPrevented = true`, otra tecla (`a`) no hace nada, Tab pasa al siguiente marcador y Enter abre el suyo, sin errores de página. La prueba unitaria nueva cubre Enter, Espacio y una tecla que no cuenta (M10 la rompe). No cubre `preventDefault` ni `'Spacebar'` (BUG-J21-3). Una pulsación mantenida repite `onSeleccionar`, que es idempotente.
- **`CerrarZoomAlQuitar`**: correcta y mínima. Sin ella el error ocurre de verdad (reproducido arriba); con ella hay 0 errores en 42 + 156 + 234 ejecuciones. `Map._onZoomTransitionEnd` (Leaflet 1.9.4, `Map.js:1718`) sale si `_animatingZoom` es falso, que es justo lo que se aprovecha. Pasar a Lista, volver a Mapa y StrictMode no se rompen (efecto con dependencia `[mapa]`; en el doble montaje de StrictMode el cierre inicial pone `false`, que ya era el valor).
- **Riesgo de `_animatingZoom`**: es un campo interno, no API. `package.json` declara `leaflet ^1.9.4`: dentro de 1.x no cambia (1.9.4 es la última), y 2.x no entra con ese rango. Si una versión futura lo renombrara, la asignación crearía una propiedad nueva sin error, el defecto volvería en silencio y la única red de seguridad sería la prueba de BUG-J21-1, que hoy es probabilística. Misma clase de riesgo que `_tiles` en `CapaTeselasGateway.js`. Aceptable si se estabiliza esa prueba y se anota la versión en el README (ya lo hace el comentario del código).

## CI (21b)

- **Lógica del YAML**: correcta. `hashFiles('web-interna/playwright.config.js')` se evalúa desde la raíz del workspace, no desde `working-directory` (el comentario lo dice bien). Los tres pasos condicionales lo usan; el del artefacto combina `failure() && hashFiles(...)`. Nombres de artefactos únicos: `playwright-report-web-interna` (frente a `playwright-report` de web-publica) y `surefire-<servicio>-utc|bogota` (la expresión `matrix.tz == 'UTC' && 'utc' || 'bogota'` es válida porque `'utc'` no es falso). Las 5 entradas de la matriz y sus nombres (`backend (servicio, tz)`) son únicos.
- **Coincide con lo que necesitan las E2E**: `npx playwright install --with-deps chromium firefox webkit` (instala los 3 motores y las librerías del sistema que a mí me faltan para WebKit), `npm run test:e2e` (la config usa `CI` para `retries: 1`, `forbidOnly` y reporte `html` en `web-interna/playwright-report`, que es la ruta que sube el artefacto). Versión de Playwright fijada en 1.64.1 igual que la imagen con que probé; `package-lock.json` coherente y `npm audit` en 0. `npm test` no recoge los `*.e2e.js` (Vitest: 92, ya comprobado).
- **Zona horaria**: `TZ` a nivel de job y `-DargLine=-Duser.timezone=<tz>` son efectivos: el XML de Surefire lo demuestra en las dos zonas, y ningún `pom.xml` define `argLine` ni usa Failsafe (todas las pruebas, incluidas las de integración, corren en Surefire, que lee la propiedad). Con la máquina del equipo ya en America/Bogota la corrida "Bogota" no distingue por sí sola; por eso confirmé la zona efectiva en el XML y repetí con UTC.
- Observaciones de CI (no bloquean): (1) la condición `hashFiles` hace que, si alguien borra o renombra `playwright.config.js`, las E2E se salten en silencio; según `00-plan.md` el Tech Lead ya decidió quitarla antes del commit (SEC-J-I29). (2) `retries: 1` en CI deja pasar una prueba inestable con éxito y solo lo anota en el log; conviene revisar a mano si aparece "flaky". (3) La CI no comprueba que `dist/` esté libre del arnés; un paso `! grep -rilE "arnes|token-simulado" dist` lo dejaría vigilado. (4) El artefacto de Surefire solo toma `target/surefire-reports`; hoy sirve porque no hay Failsafe. (5) Los archivos `web-interna/playwright.config.js`, `web-interna/.gitignore`, `dev/` y `e2e/` están sin seguir en git: hay que añadirlos (`git add`) o el job `web-interna` correrá sin E2E y sin avisar. (6) Los nombres de los jobs de backend cambiaron: hay que actualizar cualquier "check requerido" de protección de ramas si algún día se configura (hoy no hay).

## Bugs y observaciones

### BUG-J21-1 (Bajo) La E2E que protege contra `_leaflet_pos` detecta la regresión solo en ~2 de 3 ejecuciones
- Responsable sugerido: frontend (E2E).
- Archivo: `web-interna/src/ubicacion/e2e/mapa.e2e.js:62-80` (prueba "Saltar el mapa y ver la lista").
- Pasos: quitar `<CerrarZoomAlQuitar />` de `MapaHallazgos.jsx:76` y correr `E2E_SIN_WEBKIT=1 npx playwright test -g "Saltar el mapa" --repeat-each=6`.
- Esperado: falla siempre. Obtenido: 8 fallos y 4 aciertos de 12; en `npm run test:e2e` completo pasó (0 fallos). Evidencia: `pageerror: Cannot read properties of undefined (reading '_leaflet_pos')`.
- Causa: el clic llega en un instante variable respecto de la animación de `fitBounds` (250 ms); a veces ya terminó.
- Corrección sugerida: provocar la animación de forma determinista (por ejemplo pulsar "Lista" nada más contar los marcadores con `page.getByRole('button', { name: 'Lista' })`, que en mis pruebas rompía 25/42 veces sin la corrección, o añadir una unitaria con Leaflet real en jsdom: `fitBounds` con animación, desmontar y avanzar 300 ms con temporizadores falsos).

### BUG-J21-2 (Bajo) La aserción "una sola vez" de WC-12 y WI-04 no detecta llamadas repetidas
- Responsable sugerido: frontend (E2E).
- Archivos: `mapa.e2e.js:170` (WC-12), `:162` (WI-04 mapa) y `busqueda.e2e.js:145` (WI-04 búsqueda): `toContainText('(1)')`.
- Pasos: en `CapaTeselasGateway.js:61` cambiar `error?.status === 401 && !this._sesionAvisada` por `error?.status === 401` y correr las E2E.
- Esperado: WC-12 falla (el contador llegaría a (2), (3)...). Obtenido: 39/39 aprobadas en Chromium; Vitest sí falla (3 pruebas).
- Causa: Playwright acepta el primer `(1)` antes de que llegue el segundo aviso, y `toContainText('(1)')` también casaría con "(10)".
- Corrección sugerida: tras esperar que carguen las teselas (o que aparezca el aviso de mapa base), exigir el texto completo con `toHaveText(/\(1\)\. En modo real/)` dentro de `expect.poll` con una espera de estabilidad, o contar las peticiones de teselas del mock y comparar con el contador.

### BUG-J21-3 (Bajo) Nada prueba que el `keydown` de Espacio evite el desplazamiento de la página
- Responsable sugerido: frontend (pruebas).
- Archivo: `web-interna/src/ubicacion/componentes/MapaHallazgos.jsx:89` (`e.originalEvent.preventDefault()`).
- Pasos: quitar esa línea y correr Vitest y la E2E: 92/92 y 39/39.
- Esperado: alguna prueba falla (Espacio con `defaultPrevented` falso desplaza la página al usar el teclado). Obtenido: ninguna.
- Verificado a mano que hoy sí funciona (Chromium y Firefox: `defaultPrevented = true` para Espacio y `false` para otras teclas).
- Corrección sugerida: en la unitaria, `const e = createEvent.keyDown(marcador, { key: ' ' }); fireEvent(marcador, e); expect(e.defaultPrevented).toBe(true)`; y una E2E de Espacio con un `window.addEventListener('keydown', ...)` que registre `defaultPrevented`.

### Observaciones (no son bugs)
- WebKit no se pudo correr en el equipo (faltan librerías del sistema); se cubrió con la imagen oficial de Playwright (117/117 dos veces). El primer intento con Docker montando el `node_modules` del equipo falló (`ERR_MODULE_NOT_FOUND`: binarios nativos de otro sistema): hay que ejecutar `npm ci` dentro del contenedor.
- La guía manual del README tiene un paso (6) que se apoya en la pestaña Red del navegador; nada de eso se ejecutó, ver WI-13/WI-15 en `06-plan-pruebas.md` (P14).
- Docs al día: 03 (D58), 09 (CI), 00-plan describen lo que hay; `00-plan.md` dice "local web-interna 91/91" y hoy son 92 (la prueba de Enter/Espacio se añadió después); trivial.

## Veredicto Iteración 21c: APROBADO CON OBSERVACIONES

Se cumple lo declarado: Vitest 92/92, `dist/` sin ningún rastro del arnés, E2E 78/78 en local (3 corridas, además de 234 y 156 repetidas con y sin carga), 117/117 dos veces en la imagen de Playwright con los 3 motores y sin inestabilidad. De las 11 mutaciones, 10 son detectadas por alguna capa (M4 solo de forma intermitente por la E2E, M9 y M10 solo por Vitest) y solo sobrevive M11; las E2E aportan valor propio (M3, M6, M8, M2). Las dos correcciones de `MapaHallazgos.jsx` son correctas y no rompen nada. El YAML del CI es coherente con lo que necesitan las E2E y el cambio de zona horaria es efectivo (364/0/0 en UTC y en Bogotá). Quedan tres hallazgos Bajos, todos de calidad de las pruebas y ninguno de producción (BUG-J21-1 a 3), y verificación pendiente en navegador real: WI-13 y la parte manual de WI-15 (guía del README), más Edge para WI-11. Ninguno bloquea el commit.
