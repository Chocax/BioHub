# Revisión de calidad 2026-11-19 (qa-tester)

**Rama:** `develop` (HEAD `8d23981`). **Modo:** solo se construyó, levantó y probó; no se tocó código ni documentos existentes. Scripts temporales y logs en el scratchpad de la sesión (`/tmp/claude-1000/-home-toji-biohub/9e516c2d-d009-4489-a3b2-4cd047058016/scratchpad`). No se usó el perfil `aiven` ni la nube.

**Calificación: 8,5 / 10.** El sistema compila, pasa todas sus pruebas y el flujo de punta a punta funciona con la separación escritura/lectura real. No hay bugs Críticos ni Altos. Los descuentos son por validación de texto laxa, formatos de error inconsistentes, un E2E intermitente y un reporte que no normaliza el departamento.

## 1. Resumen de ejecución

| Qué | Resultado |
|---|---|
| `mvn -B -ntp verify` (raíz, 5 módulos, Testcontainers incluidos) | **BUILD SUCCESS**, 5 min 7 s. 1.173 pruebas, 0 fallos, 0 errores, 4 omitidas |
| api-gateway | 200 pruebas, 0 fallos |
| servicio-cientifico | 550 pruebas, 0 fallos, 3 omitidas (`PublicadorHallazgoE2ETest`, `PublicadorBrokerDesplegadoTest`, `BusquedaInterna10kRendimientoTest`: opt-in por propiedad/variable) |
| servicio-equipos | 15 pruebas, 0 fallos |
| servicio-consulta-publica | 368 pruebas, 0 fallos |
| servicio-reportes | 40 pruebas, 0 fallos, 1 omitida (`ConsumidorBrokerDesplegadoTest`: opt-in) |
| web-interna `npm ci` / `npm test` / `npm run build` | OK / 98 de 98 (6 archivos) / OK (482 ms) |
| web-publica `npm ci` / `npm test` / `npm run build` | OK / 34 de 34 (3 archivos) / OK (329 ms) |
| web-interna E2E Playwright (Chromium + Firefox; WebKit omitido) | 78 de 78 |
| web-publica E2E Playwright (Chromium + Firefox; WebKit omitido) | 1.ª corrida 37 de 38 (1 intermitente, QA-R-3); 2.ª corrida 38 de 38; el caso suelto pasó 3 de 3 |
| Colección Bruno (`@usebruno/cli` 4.3.0, entorno `local`) | Sin semilla: 119 de 127 tests (5 peticiones fallan por datos). Con la semilla de CI (`infra/ci/SembrarHallazgos.java`): **73 peticiones, 127 de 127** |
| Flujo propio de punta a punta por el gateway (`flujo.mjs`) | 64 comprobaciones: 63 OK; la 1 "fallida" era error de mi script (ver 3.1) |
| Cobertura (JaCoCo) | No configurada en los poms; no se midió |

No se pudo ejecutar: WebKit (no hay librerías del sistema; ya documentado, `E2E_SIN_WEBKIT=1`), pruebas opt-in contra broker desplegado, carga/rendimiento 10k, ni nada contra las VMs ni Aiven (fuera de alcance).

Observaciones de entorno:
- `docker compose up -d` recreó `keycloak` y `redis` (configuración distinta de la que tenían) y creó `mysql-analitica` y `rabbitmq-usuarios`. Quedan sanos: `keycloak`, `mysql-*` (4) y `redis` en `healthy`, `rabbitmq` en `healthy`. `rabbitmq-usuarios` es un trabajo de una sola corrida: termina en `Exited (0)` por diseño y dice "Sin contraseña para cientifico_mq/consulta_mq/reportes_mq: se omite" porque el `.env` local (del 8 de nov) no tiene las variables `RABBITMQ_CIENTIFICO_*`, `RABBITMQ_CONSULTA_*` ni `RABBITMQ_REPORTES_*` de `.env.example`; los servicios siguen usando el usuario administrador del broker.
- `npm ci` avisa `EBADENGINE`: jsdom 30 pide Node `^22.22.2`; la máquina tiene 22.22.1 (todo funcionó igual).
- Los jars se reconstruyeron con `verify` y los 5 servicios arrancaron con perfil `local` desde un cwd fuera del repo (los `.env` de `servicio-cientifico` y `servicio-consulta-publica` tienen datos de Aiven; no se usaron). Se leyó `../../.env` raíz exportándolo. Arranque: los 5 en `UP` en menos de 60 s. Sin `ERROR` en logs; solo avisos de Flyway (MySQL 8.4 más nuevo que el soporte probado y `outOfOrder` activo en reportes).
- Al terminar se detuvieron los 5 servicios Spring y las dos instancias de Vite (puertos 8080-8084, 5173, 5174 libres). Docker queda arriba.

## 2. Bases de escritura y lectura (comprobado con SELECT)

| Comprobación | Resultado |
|---|---|
| Escritura en `mysql-transaccional` (esquema `cientifico`) | El hallazgo del flujo está en `hallazgo` con `version=2`, `estado_conservacion=EN_PELIGRO`, `categoria_uicn=EN`; `hallazgo_version` tiene 2 filas (v1 `investigador1`, v2 `cientifico1` con justificación); `hallazgo_auditoria` registró los cambios campo a campo |
| Coordenadas | No existen columnas `latitud`/`longitud` en `hallazgo`. Están en `ubicacion_hallazgo.coord_cifrada` (varbinary, AES con `clave_version=v1`); el API de mapa las descifró exactas (`[-73.36712, 5.53214]`) solo para `cientifico_alto_rango` |
| Esquemas de `mysql-transaccional` | `cientifico` y `equipos`; usuarios `cientifico_app` y `equipos_app` (uno por esquema). Equipo asignado en `equipos.equipo_trabajo` (`ASIGNADO`, custodio `investigador1`) |
| Proyección en `mysql-lectura` (`consulta_publica.ficha_publica`) | El mismo `hallazgo_id` con `version=2`, `EN_PELIGRO`; la tabla **no tiene ninguna columna de coordenadas ni de investigador**. Solo existe el usuario `consulta_app` |
| Sincronización por eventos | Alta y reclasificación llegaron a lectura en menos de 1 s (consulta de sondeo cada 1 s); también a `mysql-analitica` (`hallazgo_reporte`, `version=2`) |
| Bases separadas | 4 servidores MySQL distintos (3307, 3308, 3309, 3310); cada servicio con su usuario |
| Redis | Claves `biohub:publica:fichas::<id>` (TTL 275 s al medirlo) y `biohub:publica:busquedas::q=...`; la reclasificación invalidó la ficha en caché (el siguiente GET devolvió `EN_PELIGRO`); las lecturas repetidas cuestan unos 10 ms. Redis también guarda contadores del gateway (`biohub:gateway:rl:*`, `key:*`) |
| RabbitMQ | Colas `consulta-publica.eventos`, `reportes.eventos`, `reportes.solicitudes` con 1 consumidor cada una, vacías. `reportes.eventos.dlq` tiene 2 mensajes viejos ("Evento de especie en peligro contiene coordenadas prohibidas"): son eventos con coordenadas de corridas de pruebas anteriores, el rechazo es el comportamiento deseado |

## 3. Flujo de punta a punta por el gateway (8080)

Usuarios de Keycloak: `investigador1`, `cientifico1`, `auditor1`, `analista1`, `admin1`, `sinrol1` (cliente `biohub-pruebas`).

| # | Paso | Resultado |
|---|---|---|
| 1 | Registrar hallazgo (`investigador1`, con lat/lon) | PASA: 201 `{hallazgoId, version:1}` + `Location` |
| 2 | Aparece en consulta pública, sin coordenadas | PASA: `GET /api/publica/fichas?q=` lo devuelve por evento; la ficha trae `ubicacionProtegida:true` y ningún campo de coordenadas; catálogo `/especies` también |
| 3 | Reclasificar (`cientifico1`) | PASA: 200 `version:2`; sin justificación da 400; con auditor 403; id inexistente 404; la ficha pública pasa a `EN_PELIGRO` |
| 4 | Historial con versiones | PASA: `/versiones` (2, la más reciente primero, con autor y justificación), `/auditoria` (campo a campo), `/comparar?desde=1&hasta=2` (solo los campos distintos); iguales 400; inexistente 404; investigador 403 |
| 5 | Asignar equipo a investigador | PASA: crear equipo 201; código duplicado 409; asignar a `auditor1` 400 ("debe ser un investigador de campo"); custodio inexistente 400; asignar a `investigador1` 200 `ASIGNADO`; filtro `estado+custodioId` lo lista; con rol investigador 403 |
| 6 | Reporte mensual asíncrono | PASA: `POST /api/reportes/solicitudes` 202 + `Location` + `PENDIENTE`; pasó a `LISTO`; resumen con el hallazgo (Boyacá, índice 100, alerta); CSV (`text/csv;charset=UTF-8`, separador `;`, coma decimal) y PDF (`%PDF`) descargados; periodo futuro y `abc` 400; investigador 403; sin token 401 |
| 7 | API key con rate limit | PASA: consumidor 201, key `bh_pub_...` con `no-store`, ruta externa 200 con `X-RateLimit-Limit/Remaining`; sin key y key inválida 401; con límite 2 la tercera petición de la ventana da 429 con `Retry-After: 60` y código `LIMITE_EXCEDIDO`; límite 0 400; `/externa/eventos/recibidos` no existe (401); revocar 204 y luego 401. Además el límite por IP del portal corta en la petición 106 con 429 y `Retry-After` |
| 8 | Coordenadas exactas con rol bajo | PASA: `ubicacion/mapa` con investigador, auditor y administrador da 403; sin token 401; con `cientifico_alto_rango` 200. Búsqueda interna y `GET /hallazgos/{id}` no devuelven coordenadas |
| 9 | Seguridad adicional | PASA: `%2e%2e` y `..` en rutas dan 400 (corregido desde la revisión anterior); `/actuator/*` distinto de `health` exige token (401) en gateway y servicios; CORS rechaza `Origin` ajeno (403) y acepta `localhost:5174`; token basura 401; `sinrol1` recibe 403 en módulos internos; consultas con `' OR 1=1 --`, `%00`, emoji, `%`, `_` no rompen nada |
| 10 | Webs | PASA: web-publica (5174) y web-interna (5173) cargan (200; la interna redirige a Keycloak "Inicia sesión en BioHub"); captura de la portada y de una búsqueda real contra el gateway (resultado mostrado); la portada pública consume 200 de `/especies` y `/especies/familias` |

### 3.1 Un "FAIL" que fue de mi prueba
La comprobación 8g de mi script esperaba `200,200,429,429` con límite 2, pero la petición 8c (que consulté justo antes) ya había abierto la ventana de 60 s; obtuve `200,429,429,429`, que es lo correcto con límite 2 por ventana. Se trata de un error del test, no del producto; no cuenta como bug.

## 4. Bugs

### QA-R-1 — Medio — Se aceptan y publican nombres con HTML (`<script>`)
- **Pasos:** `POST /api/cientifico/hallazgos` (investigador) con `"nombreCientifico":"Xss <script>alert(1)</script>"` y el resto válido.
- **Esperado:** 400 `DATOS_INVALIDOS`: un nombre científico solo tiene letras, espacios, `.`, `'` y `-` (la propia ruta `/especies/foto` ya exige eso y devolvió 400 para este nombre).
- **Obtenido:** 201; después `GET /api/publica/fichas?q=Xss` lo devuelve tal cual (`"nombreCientifico":"Xss <script>alert(1)</script>"`). React lo escapa, pero queda en la base, en el portal público, en la API externa y en cualquier cliente no web (CSV, integraciones).
- **Archivo probable:** `servicio-cientifico/.../registro/service/` (validador de datos del hallazgo; solo valida longitud y coordenadas).
- **Responsable sugerido:** backend (registro).

### QA-R-2 — Bajo — JSON malformado devuelve el error por defecto de Spring
- **Pasos:** `POST /api/cientifico/hallazgos` con cuerpo `{bad` y `Content-Type: application/json`.
- **Esperado:** `application/problem+json` con `codigo` (contrato §2, formato de error estándar).
- **Obtenido:** 400 `application/json` con `{"timestamp":...,"status":400,"error":"Bad Request","path":"/api/cientifico/hallazgos"}`, sin `codigo`.
- **Archivo probable:** el manejador de excepciones de `servicio-cientifico` (falta `HttpMessageNotReadableException`). No se probó en los otros servicios.
- **Responsable sugerido:** backend.

### QA-R-3 — Bajo — E2E intermitente en web-publica (Firefox)
- **Pasos:** `cd web-publica && E2E_SIN_WEBKIT=1 npm run test:e2e` (falló 1 de 5 corridas, 2 completas y 3 del caso suelto; las otras pasaron).
- **Esperado:** "HU-06.2: texto de 1 letra se valida en el cliente sin llamar a la API" siempre en verde.
- **Obtenido:** `expect(pedidas.length).toBe(antes)` con `Expected: 2, Received: 4`.
- **Causa probable:** carrera en el propio test: toma `antes` en cuanto el encabezado es visible, pero la portada sigue pidiendo `/especies/familias` y `/especies`. Es problema del test, no del producto.
- **Archivo:** `web-publica/e2e/portal.spec.js:110-119` (esperar a `networkidle` o descartar las peticiones de carga). **Responsable:** frontend.

### QA-R-4 — Bajo — El reporte no normaliza el departamento (código frente a nombre)
- **Evidencia:** en `reportes.hallazgo_reporte.departamento` hay `15` (eventos con código, como los de la colección Bruno) y `Boyacá` (evento real de `registro`). El contrato §7.3 permite ambos ("código DANE o nombre oficial").
- **Esperado:** un mismo departamento cuenta como una sola zona en los índices y la alerta.
- **Obtenido (por inspección de datos, no se generó un reporte con ambos):** se agrupa por el texto crudo, así que `15` y `Boyacá` serían dos zonas. Hoy no ocurre con el flujo real porque `registro` emite el nombre.
- **Archivo probable:** `servicio-reportes/.../eventos` (consumidor que guarda `hallazgo_reporte`). **Responsable:** backend (reportes).

### QA-R-5 — Bajo — `familia=` vacío en la búsqueda pública devuelve 0 resultados
- **Pasos:** `GET /api/publica/fichas?familia=`.
- **Esperado:** 400 (contrato: 1–100 caracteres) o ignorar el filtro.
- **Obtenido:** 200 con `total:0`, aunque haya fichas. Puede ocultar datos a un cliente externo que mande el parámetro vacío.
- **Archivo probable:** `servicio-consulta-publica/.../service/CriterioBusquedaPublica.java`. **Responsable:** backend.

### QA-R-6 — Bajo — Documentación local desactualizada
- `README.md` §4b dice "son 7" contenedores; el compose tiene 8 servicios (`mysql-analitica`, `rabbitmq-usuarios` de una sola corrida).
- La colección Bruno en entorno `local` falla 5 peticiones (8 tests) de `ubicacion/` si antes no se siembran los hallazgos fijos de `infra/ci/SembrarHallazgos.java` con la misma llave; el README no lo dice (solo el CI lo hace).
- `.env` existentes no traen `RABBITMQ_*_USER/PASSWORD`; `rabbitmq-usuarios` los omite sin avisar al levantar.
- **Responsable:** devops / documentación.

### Observaciones (no son bugs)
- El portal público muestra errores 404 en consola por las fotos de especies sin foto pública (esperado por contrato; ruido en la consola).
- Quedan datos de prueba en las bases locales: hallazgos `Qarevision pruebaensis` y `Xss <script>...` (este solo se puede quitar por SQL), los 3 hallazgos de la semilla de CI, un equipo `QA-...`, un consumidor `QA Consumidor ...` y usuarios que crea la colección Bruno en Keycloak. Todo en Docker local.
- `docs/equipo/00-plan.md` aparece modificado en el árbol de trabajo: no fue obra de QA.

## 5. Cobertura de criterios de aceptación que se pudieron ejecutar

| Historia | Criterio | Cómo se comprobó | Estado |
|---|---|---|---|
| HU-01 Registrar hallazgo | Crea hallazgo con versión 1, valida campos, guarda ubicación aparte | Flujo 1 + casos 400 (vacío, fecha futura, lat sin lon, fuera de rango, coordenadas en texto) + SELECT | Cumple (ver QA-R-1) |
| HU-02 Plantilla de atributos | Fichas con atributos | Solo por Bruno/pruebas Maven (no se repitió a mano) | Parcial |
| HU-03 Reclasificar | Justificación obligatoria, nueva versión | Flujo 3 | Cumple |
| HU-04 Equipos | Asignar solo a investigador, estados, filtros | Flujo 5 | Cumple |
| HU-05 Historial | Versiones, auditoría, comparar | Flujo 4 | Cumple |
| HU-06.1/.5 Modelo de lectura y reclasificación en portal | Evento actualiza lectura y caché | Flujo 2-3 + SELECT + Redis | Cumple |
| HU-06.2/.3 Buscar y ficha protegida | Filtros, validaciones, sin coordenadas | Flujo 2, 14 consultas de borde | Cumple (ver QA-R-5) |
| HU-06.4 Caché y resiliencia | Caché Redis, límite por IP | TTL, invalidación y 429 al 106 | Cumple; modo degradado no probado |
| HU-07 Mapa | Solo alto rango, descifrado correcto | 403/401/200 y coordenadas | Cumple |
| HU-08 API externa | Key, 401, 429 | Flujo 7 | Cumple |
| HU-09 Reportes | 202 asíncrono, CSV y PDF | Flujo 6 | Cumple (ver QA-R-4) |
| HU-10/12 Usuarios y keys | Roles, 403, revocar | Flujo 7-9 y Bruno 21-29 | Cumple |
| HU-13 Búsqueda interna | Sin coordenadas para roles bajos | Flujo 3g y Bruno | Cumple |
| RNF13 Navegadores | Chromium y Firefox (E2E) | 78 y 38 pruebas | Cumple; WebKit no ejecutado |
| ASR6 / RNF9 Coordenadas protegidas | No salen de `servicio-cientifico` | Esquemas, SELECT, respuestas | Cumple |

## 6. Bien hecho
- Separación CQRS real y verificable: la tabla de lectura no tiene coordenadas, las coordenadas se guardan cifradas con versión de llave, el investigador no viaja al portal.
- Propagación por eventos rápida (menos de 1 s) y consistente en lectura y analítica, con caché invalidada al reclasificar; el broker rechaza (a DLQ) eventos con coordenadas.
- 1.173 pruebas Java con Testcontainers reales y cero fallos; 132 pruebas unitarias web y 116 E2E de navegador en verde.
- Respuestas de error consistentes con el contrato en lo funcional (400/401/403/404/409 con `codigo`), 403 claro entre roles, y los puntos débiles de la revisión anterior (`%2e%2e` en el gateway) quedaron corregidos.
- `actuator` cerrado salvo `health`, CORS estricto y cabeceras de seguridad (`nosniff`, `X-Frame-Options: DENY`).
- Reporte asíncrono con CSV adaptado a Excel en español y PDF; límites de API key y de IP con `Retry-After`.
- La colección Bruno pasa 127 de 127 con la semilla, y el CI ya la ejecuta de punta a punta.
- `levantar.ps1` y `docker-compose.yml` dejan el entorno reproducible; los pasos se replicaron en bash sin sorpresas.

## 7. Veredicto
**APROBADO** para la entrega: build y pruebas en verde, flujo completo funcionando y bases separadas y sincronizadas. Antes de la sustentación conviene corregir QA-R-1 (validación de texto) y QA-R-2, y estabilizar el test de QA-R-3.
