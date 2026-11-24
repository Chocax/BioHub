# Revisión 2026-11-19: arquitectura, RNF y ASR

**Rama:** `develop` (HEAD `8d23981`, PR #17) · **Autor:** arquitecto-software · **Tipo:** revisión de solo lectura. No se modificó código ni documentos, no se arrancaron servicios, no se usó el perfil `aiven` ni se tocó Docker.
**Línea base:** `docs/revision/asr-rnf.md` (2026-11-08, HEAD `6a5495a`). Desde entonces entraron los PR #8 a #17 (`git log 6a5495a..develop`).
**Fuentes de verdad:** SRS §2.2 y §2.2.1 (RNF1-13, ASR1-9), SAD §4-§7, C4 nivel 2 y `03-arquitectura.md` (D1-D65). **Apoyo:** `10-verificacion-sad.md`, `pruebas-carga/`, `.github/workflows/ci.yml` y el código.
**CI:** verde en `8d23981` (GitHub Actions, run 44485832271, consultado con `gh run list`).

**Regla de estado (la misma del 08-11):**
- **Cumple:** la táctica está implementada y hay una prueba automatizada o una medición que demuestra la medida del escenario.
- **Parcial:** la táctica está implementada, pero falta la medición o se midió en condiciones distintas.
- **No cumple:** la táctica no existe.

---

## 1. Resumen ejecutivo

| Estado | RNF (13) | ASR (9) |
|---|---|---|
| **Cumple** | 4: RNF9, RNF10, RNF11, RNF12 | 4: ASR6, ASR7, ASR8, ASR9 |
| **Parcial** | 9: RNF1, RNF2, RNF3, RNF4, RNF5, RNF6, RNF7, RNF8, RNF13 | 5: ASR1, ASR2, ASR3, ASR4, ASR5 |
| **No cumple** | 0 | 0 |

**Delta frente al 08-11:** RNF pasa de 3/6/4 a 4/9/0 y ASR de 3/3/3 a 4/5/0. Todas las tácticas del SAD tienen ahora código: registro e historial (HU-01/02/03/05), reporte asíncrono (HU-09) y evidencias. Ningún "No cumple" sigue en pie. Lo que impide que ASR1-ASR5 lleguen a Cumple es la **medición**: en `pruebas-carga/resultados/` no hay ninguna corrida nueva de ASR1, ASR2, ASR3 ni ASR4. `k6/registro-hallazgo.js:1` sigue diciendo "PENDIENTE - NO MEDIBLE", aunque HU-01 ya existe.

**Riesgo principal.** D65 (fotos públicas, 2026-11-19) abrió un camino **síncrono** desde el tráfico público hacia el Servicio Científico. Ese camino lee la base de escritura y el disco, y decodifica y recodifica cada imagen (ARQ-R-1). Rompe la regla 2 de 03 §J1 ("consulta-publica no llama a servicio-cientifico", línea 205) y la justificación de ASR2 en SAD §4.7 ("el tráfico público nunca llega a esa base"). Además, lo hace justo en el escenario del enunciado: el anuncio de una especie nueva, con la caché fría. Es el único hallazgo que hace **retroceder** el diseño.

---

## 2. Tabla RNF1–RNF13

| ID | Medida (SRS) | Táctica y dónde está | Evidencia | Estado | Delta vs 08-11 |
|---|---|---|---|---|---|
| **RNF1** | Pico ≥ 60x: ≥ 95 % sin error de servidor, secundarias desactivables, estabilización < 5 min | Servicio aislado. Caché Redis tolerante: `ConsultaFichasService.java:29,38` (`@Cacheable(sync=true)`) y `CacheTolerante.java:16`. Strategy completa/degradada: `EstrategiaBusqueda.java:9`, `BusquedaDegradada.java:11`, `ElectorEstrategiaBusqueda.java:7`. `MonitorCarga.java:29` con histéresis. Límite por IP de 120/min (`api-gateway/.../application.yml:92`). Réplicas + NGINX solo en `pruebas-carga/compose-carga.yml` | `DegradacionDinamicaWebTest`, `RedisCaidoIntegracionTest`. k6 `asr1-1replica-n60.json`/`asr1-2replicas-n60.json` (0 % de fallos, p95 ≈ 3 ms) **sin los metadatos de D42** (los `parametros` solo tienen `MULT, N, T…`) | **Parcial** | Sin cambios en la evidencia. **Nuevo:** el monitor no observa `/api/publica/especies` ni `/foto`, que son la portada desde D65 (ARQ-R-5). Además, cada visita a la portada genera 14 peticiones frente al límite por IP (ARQ-R-11) |
| **RNF2** | Carga científica ≤ 5 s con pico público x60 | Servicio y base de escritura propios (D39). Heap de 512 MB por proceso (`levantar.ps1:530`, la única forma de bulkhead que hay). Registro ya implementado (`HallazgoService.java:77-120`) | No hay corrida combinada. `k6/registro-hallazgo.js` sigue siendo un esqueleto | **Parcial** | El registro ya existe. **Retroceso de diseño:** D65 hace que el pico llegue al científico (ARQ-R-1) |
| **RNF3** | El reporte mensual no degrada el registro diario más de 10 % | Solicitud 202 + cola `reportes.solicitudes` + worker (`ColaSolicitudesReporte.java:48-67`, `SolicitudesReporteService.java:73-131`). Base analítica alimentada por eventos y cruce agregado en SQL (`HallazgoReporteRepository.java:32-41`). Nunca toca la base `cientifico` | `SolicitudesReporteIntegracionTest` (MySQL y RabbitMQ reales), `CalculadorIndicePresionTest`. **No hay** medición del registro con y sin reporte | **Parcial** | Pasa de **No cumple** a Parcial (PR #13 y D61). Riesgo: los endpoints analíticos síncronos (ARQ-R-10) |
| **RNF4** | Registro con archivos ≤ 5 s | `POST /api/cientifico/hallazgos` + `POST …/{id}/evidencias` multipart (D62, `EvidenciaController.java:47`); el gateway no interpreta el multipart (D51) | `EvidenciasMySqlTest`, `RegistroConUbicacionTest`. Sin k6 | **Parcial** | Pasa de **No cumple** a Parcial |
| **RNF5** | Consulta pública ≤ 2 s con carga científica simultánea | CQRS: `ConsumidorEventos.java:62` → `ProcesadorHallazgos.java:40` → `EscrituraFichasJdbc.java:21-24` ("gana la versión mayor"); caché TTL 5 min / 60 s | HU-06 c.3 con 10.000 fichas y caché fría: p95 de 14 ms (1 VU) y 58,6 ms (10 VU) (`hu06-resumen-medianas.json`), **sin** carga científica simultánea. No hay par `asr4-sin`/`asr4-con` | **Parcial** | Hay medición nueva (HU-06), pero no en la condición del escenario. **Nuevo:** cada evento borra 3 cachés completas (ARQ-R-6) |
| **RNF6** | Buscar ficha y registrar hallazgo en < 5 min, ≤ 3 pasos, error ≤ 5 % | Buscar: `Inicio.jsx` → `Ficha.jsx`. Registrar: `web-interna/src/paginas/NuevoHallazgo.jsx` | E2E `portal.spec.js` en 3 motores. No hay prueba de usabilidad con personas (`07` línea 227: UX-01..05 "sin automatizar") | **Parcial** | Ya existe la mitad "registrar" |
| **RNF7** | ≥ 90 % entiende por qué no ve coordenadas | `AvisoUbicacionProtegida.jsx`, `ubicacionProtegida: true` | E2E del aviso; no hay prueba con usuarios | **Parcial** | Sin cambios |
| **RNF8** | Campo personalizado en < 2 min, éxito ≥ 90 % | Columna JSON (`V20261104_1000__registro_hallazgo.sql:21,50`), plantillas versionadas (`FichaPlantillaService.java:52-72`) y pantalla `Plantillas.jsx` | `FichaPlantillaServiceTest`. Sin prueba de usabilidad. **La validación contra la plantilla no está conectada** (ARQ-R-3) | **Parcial** | Pasa de **No cumple** a Parcial |
| **RNF9** | Coordenadas cifradas AES-256, nunca en claro | `CifradorAesGcm.java:24` (GCM, AAD = UUID). Decorator `ConfiguracionUbicacion.java:94-97`. Sin columnas lat/lon. EXIF eliminado de las fotos (D62, `LimpiadorMetadatos`) | `QaCifradoYReposoTest`, `MigracionesUbicacionTest`, `LimpiadorMetadatosTest` | **Cumple** (con brecha) | Sin cambios en el cifrado. **Brecha nueva:** los atributos flexibles admiten coordenadas en texto plano (ARQ-R-3) |
| **RNF10** | Historial inmutable sin excepción de rol | Builder `VersionHallazgo.java:120-176`; `hallazgo_version` y `hallazgo_auditoria` con CHECK; `GRANT` sin UPDATE/DELETE (`deploy/aiven/03-registro-historial-append-only.sql.example:31-37`, D60); ningún endpoint modifica el historial | `MigracionesRegistroTest.conLosPermisosPropuestosElHistorialEsAppendOnly` (MySQL real: UPDATE/DELETE rechazados), `HistorialAppendOnlyScriptTest`, `VersionHallazgoBuilderTest` | **Cumple** (en Aiven) | Pasa de **No cumple** a Cumple. En el MySQL local de la demo no se aplica (ARQ-R-7) |
| **RNF11** | 60/min por consumidor, configurable | `ApiKeyRateLimitFilter`, `LimitadorRedis.java:22-34` (Lua atómico), `LimitadorConRespaldo` | `GatewayIntegracionTest`; k6 `asr8-gateway*.json` (120 respuestas 200 en 2 ventanas y el resto 429 con `Retry-After`) | **Cumple** | Hay evidencia de carga nueva (PR #9) |
| **RNF12** | Migración sin cambios para los externos | `RutasGateway.java:21` (`/api/v1/externa`), `ProxyService.java:108-119`, destinos por variable | `ProxyServiceTest`, Bruno `gateway/` | **Cumple** | Sin cambios |
| **RNF13** | 2 últimas versiones de Chrome, Firefox, Edge y Safari | Playwright en Chromium, Firefox y WebKit para web-publica y web-interna (`ci.yml:38,72`); Edge solo con `E2E_EDGE=1` (`web-publica/playwright.config.js:15`) | CI verde | **Parcial** | web-interna ya tiene E2E en CI (solo del módulo `src/ubicacion`). Faltan Edge y la matriz de versiones |

---

## 3. Tabla ASR1–ASR9

| ID | Táctica del SAD | Implementación (archivo:línea) | Evidencia | Estado | Delta |
|---|---|---|---|---|---|
| **ASR1** | Servicio aparte, bulkhead, NGINX + réplicas, degradación, Redis (§4.7, §5.1, §6.5) | Las mismas de RNF1. Réplicas solo en el banco de carga (permitido por §5.3) | k6 sin metadatos de D42; la degradación nunca se activó bajo carga; no hay corrida por el gateway | **Parcial** | Igual; superficie nueva sin medir (especies + foto) |
| **ASR2** | Recursos reservados + base de escritura propia; "el tráfico público nunca llega a esa base" (§4.7) | Base propia (D39). Sin límites de CPU (ARQ-24). **D65:** `FotosCientificoHttp.java:99-104` → `FotoPublicaController.java:33-34` (`permitAll`) → `EvidenciaService.java:144-155` (consulta `hallazgo_evidencia` en la base de escritura) → `FotoPublica.java:29-30` (`ImageIO.read` de hasta 9 MB) | Ninguna | **Parcial** | El registro existe, pero la invariante de aislamiento **retrocedió** (ARQ-R-1) |
| **ASR3** | Cola + worker + base analítica (§4.7, §5.1, SD-08) | `ColaSolicitudesReporte.java:58` (listener), `SolicitudesReporteService.java:102` (`procesar`), `:89` y `ColaSolicitudesReporte.java:70` (mensual programado) | `SolicitudesReporteIntegracionTest`. Sin medición del Δ ≤ 10 % | **Parcial** | Pasa de **No cumple** a Parcial |
| **ASR4** | CQRS + caché distribuida + Observer (§4.7, §6.5) | Las mismas de RNF5 | HU-06 sin carga científica; no hay K5 | **Parcial** | Igual (ARQ-R-6 es nuevo) |
| **ASR5** | Columna JSON + Fábrica de Atributos (SD-09); "los hallazgos conservan la versión de plantilla" | JSON y versionado de plantilla, sí. **No hay** fábrica. `HallazgoService.java:92` guarda `plantillaVersion = null`. `FichaPlantillaService.validarValores` (`:95`) no se llama desde el código de producción | `FichaPlantillaServiceTest` (en aislamiento). Sin usabilidad | **Parcial** | Pasa de **No cumple** a Parcial |
| **ASR6** | AES-256, llaves fuera de la BD, Adapter/Decorator/Strategy/Observer | Sin cambios: `CifradorAesGcm`, `ProveedorDeLlavesEntorno`/`KeyStore`, `PoliticaAccesoUbicacion.java:38,60`, `ObservadorUbicacion.java:29` | Las de RNF9 + `ArquitecturaUbicacionTest`, `EstrategiasVisibilidadTest` | **Cumple** | Sin cambios (ver la brecha de ARQ-R-3) |
| **ASR7** | Append-only + Builder + GRANT INSERT/SELECT (§4.7, §5.2, §6.2.1) | Las de RNF10 | Las de RNF10 | **Cumple** | Pasa de **No cumple** a Cumple |
| **ASR8** | Rate limiting + API Keys en el gateway | D45-D47 | `GatewayIntegracionTest` + k6 ASR8 | **Cumple** | Hay evidencia de carga |
| **ASR9** | Proxy inverso | D45 | `ProxyServiceTest` | **Cumple** | Igual |

---

## 4. Arquitectura

### 4.1 Estilo
- **Arquitectura basada en servicios:** se cumple. Hay 4 servicios de dominio y un gateway propio, cada uno con su `pom` y su Flyway. Ningún servicio importa código de otro: el grep de `import co.edu.unbosque.biohub.<x>` solo encuentra el paquete propio en los 5 proyectos.
- **API Gateway propio:** `ProxyService.java:30,90` y `ProxyController`, con circuit breaker por destino (`CircuitoServicios.java:30-108`, D52), límites en Redis y validación del JWT. Es una sola instancia sin balanceador ni TLS, y no hay ADR que lo acepte (ARQ-R-9).
- **Eventos en topología broker:** exchange topic `biohub.eventos` con una cola + DLQ por consumidor (consulta-publica y reportes). El publicador espera la confirmación del broker y pone `user_id` (`PublicadorEventos.java:146-156`). Publica **después del commit** (`PublicadorCambiosHallazgo.java:37`), sin outbox (ARQ-R-4).
- **Llamadas síncronas entre servicios** (no figuran en el SAD ni en el C4): consulta-publica → científico (D65, fotos) y equipos → gateway (`GatewayCustodioAdapter.java:25-31`, para validar al custodio en Keycloak).

### 4.2 Separación de bases
| Base | Quién escribe | Quién lee | Estado |
|---|---|---|---|
| `mysql-transaccional` (`cientifico`, `equipos`) | científico, equipos | científico, equipos y, **desde D65, el tráfico público a través de la foto** | Separada, pero ya no aislada del público |
| `mysql-lectura` (`consulta_publica`) + Redis | Solo el consumidor de eventos (`EscrituraFichasJdbc`) | consulta-publica | Conforme (CQRS) |
| `mysql-analitica` (`reportes`) | Proyector de eventos (`ProyectorHallazgosService.java:28-65`) + resultados de reportes | reportes | Conforme. En Aiven comparte servidor con la lectura (D39) |
| `mysql-gateway` | gateway | gateway | Conforme |

Local: 4 contenedores MySQL (`docker-compose.yml:17,36,53,70`). Aiven: 2 servicios (D39).

### 4.3 CQRS alimentado por eventos
Es real y está probado. El modelo de lectura es un snapshot por hallazgo con "gana la versión mayor" (`EscrituraFichasJdbc.java:21-24`), y la caché se invalida después del commit. Reportes es idempotente por `evento_id` (`ProyectorHallazgosService.java:30`) y por versión (`HallazgoReporte.java:70`). Falta una forma de recuperar eventos perdidos (ARQ-R-4).

### 4.4 Aislamiento `registro` ↔ `ubicacion`
- `grep "import ...cientifico.ubicacion"` en `registro/`: **0 resultados**. A la inversa: **0**. `eventos` no importa a ninguno de los dos. ArchUnit lo exige (`ModuloRegistroNoDependeDeUbicacionInternaTest.java:47-48`, D53).
- La comunicación va solo por eventos de dominio: `HallazgoService.java:107` → `ObservadorUbicacion.java:29` (síncrono, misma transacción) y `:116` → `PublicadorCambiosHallazgo.java:37` (AFTER_COMMIT). Coincide con SAD §6.4.2.
- No hay acoplamiento por SQL: `ubicacion` mantiene su propia copia (`ubicacion_hallazgo`) y `registro` no consulta tablas `ubicacion_*`.
- **Acoplamiento oculto:** `TraductorErroresDeUbicacion.java:21-22,30-53` reconoce las excepciones de ubicación por su nombre simple e invoca `campo()` por reflexión. ArchUnit no lo ve (ARQ-R-12).

### 4.5 Capas
- `service → controller`: 0 violaciones en los 5 servicios (ARQ-18 cerrado; los DTO están en `service/vista`).
- `domain → service/infrastructure/controller`: 0.
- `service → infrastructure` concreta (sin puerto): consulta-publica (`ConsultaEspeciesService.java:7`, `ConsultaFichasService.java:13`, `ProyectorFichas.java:9`, `FotosEspeciesService.java:12`) y equipos (`EquipoTrabajoService.java:7`). SAD §7.1 dice que las dependencias apuntan hacia `domain` (ARQ-R-13).
- `registro.domain` extiende `JpaRepository` de Spring Data. Es pragmático, pero el repositorio del historial hereda `delete*` (ARQ-R-7).

---

## 5. Patrones de diseño realmente implementados

| Patrón | Dónde (archivo:línea) | ¿Coincide con SAD/03? |
|---|---|---|
| API Gateway / proxy inverso | `ProxyService.java:30,90,108` | Sí (SAD §4.5, ASR8/9) |
| Circuit breaker | `CircuitoServicios.java:30-108`, usado en `ProxyService.java:63`; config `application.yml:98-99` | Sí en el gateway (D52). **No existe** en consulta-publica → científico ni en equipos → gateway |
| Rate limiting | Ventana fija con Lua `LimitadorRedis.java:22-34`, respaldo en memoria `LimitadorConRespaldo` | Sí (D46). No es token bucket: es ventana fija, con la ráfaga doble en el cambio de ventana aceptada en D46 |
| Cache-aside | `ConsultaFichasService.java:29,38`, `ConsultaEspeciesService.java:28,37` (`sync=true`); Decorator `CacheTolerante.java:16` | Sí. SAD §6.5 habla de un Decorator del repositorio; se implementó con la abstracción de caché de Spring más un Decorator de `Cache`. Mismo efecto |
| Caché en memoria de fotos | `FotosEspeciesService.java:28-60` | No está en el SAD. **Sin single-flight** (ARQ-R-1) |
| Publicación de eventos tras el commit | `PublicadorCambiosHallazgo.java:37`, `ColaSolicitudesReporte.java:48` | Sí (SAD §6.4.2) |
| Outbox | — | **Ausente.** Recomendado en `04-contrato-api.md:641` y riesgo R2 de 03 (línea 543) |
| Consumidor idempotente + DLQ | consulta-publica `EscrituraFichasJdbc.java:21-24`; reportes `ProyectorHallazgosService.java:30,64`; `default-requeue-rejected: false` | Sí (D23, SD-04) |
| Append-only / historial de versiones | `VersionHallazgo` + `hallazgo_auditoria` + GRANT | Sí. No es Event Sourcing: es estado actual mutable más historial inmutable, consistente con SAD §4.7 |
| Builder | `VersionHallazgo.java:120-176`; `HallazgoService` como director (`:99,169`) | Sí (SAD §6.2.1) |
| Strategy (visibilidad) | `PoliticaAccesoUbicacion.java:38`, `VisibilidadExacta`, `VisibilidadGeneralizada` | Sí (SAD §6.4.1) |
| Strategy (búsqueda degradada) | `EstrategiaBusqueda.java:9`, `BusquedaCompleta.java:6`, `BusquedaDegradada.java:11`, `ElectorEstrategiaBusqueda.java:7` | Sí (SAD §6.5) |
| Decorator (repositorio de ubicaciones) | `ConfiguracionUbicacion.java:94-97` (JPA → cifrado → auditoría) | Sí (SAD §6.3.2) |
| Adapter | `CifradorAesGcm.java:24` implementa `CifradorCoordenadas`; `ProveedorDeLlavesKeyStore.java:36`; `AlmacenEvidenciasDisco` implementa `AlmacenEvidencias`; `GatewayCustodioAdapter` implementa `ValidadorCustodioPort` | En parte. El Adaptador de Mensajería **no tiene** la interfaz `CanalEventos`: `PublicadorCambiosHallazgo` depende del `PublicadorEventos` concreto |
| Observer | `ObservadorUbicacion.java:29,36` (`@EventListener`) + `PublicadorCambiosHallazgo.java:37` (`@TransactionalEventListener`) | Sí. Con el mecanismo de Spring y sin la interfaz `ObservadorHallazgos` del diagrama de clases |
| Worker asíncrono de reportes | `ColaSolicitudesReporte.java:58-67`, `SolicitudesReporteService.java:102-131`, `@Scheduled` `:70` | Sí (SD-08, D61) |
| Factory (Fábrica de Atributos, SD-09) | — (`switch` en `FichaPlantillaService.java:147-155`) | **Ausente.** Solo está en SD-09, no en la Tabla 3 |
| Bulkhead | Solo `-Xmx512m` por proceso (`levantar.ps1:530`) y `cpus` en el compose de carga | **Ausente en código y despliegue** (ARQ-24 sigue abierto) |
| Redundancia con NGINX | Solo `pruebas-carga/compose-carga.yml` | En parte (§5.3) |

**Documentados en el SAD con otro nombre en el código** (conviene una tabla de equivalencias para la sustentación): `ServicioCifrado`/`AdaptadorCifradoJks` → `CifradorCoordenadas`/`CifradorAesGcm` + `ProveedorDeLlavesKeyStore`; `CanalEventos` → no existe; `ManejadorEventosIntegracion` → `PublicadorCambiosHallazgo`; `PublicadorEventosDominio` → `ApplicationEventPublisher`; `RepositorioHallazgosVersiones` → `VersionHallazgoRepository`; `ServicioHallazgos` → `HallazgoService`.

---

## 6. Coherencia código ↔ SAD/C4 (desviaciones)

| # | SAD / C4 dice | Código hoy | ¿Hay ADR? |
|---|---|---|---|
| 1 | §4.7 ASR2: "el tráfico público nunca llega a esa base"; 03 §J1 regla 2 | D65: consulta-publica llama al científico, que lee `cientifico` y el disco | **No.** D65 justifica la foto, pero no menciona la regla 2 ni ASR2 |
| 2 | C4-2: Consulta Pública solo usa la BD de lectura y Redis | También llama por HTTP al Servicio Científico. Equipos llama al gateway | C4 desactualizado |
| 3 | §5.1: varias instancias del gateway y del científico tras NGINX | Una instancia de cada uno | No (sigue abierto desde el 08-11) |
| 4 | §5.2: TLS en la frontera | HTTP local | No |
| 5 | §5.1: health checks y reinicio | Hay probes, pero nadie reinicia los servicios | No |
| 6 | §5.1: bulkhead | Ausente (ARQ-24) | No |
| 7 | §4.5: Reportes con base propia | En Aiven comparte servidor con la lectura pública | Sí (D39) |
| 8 | §5.2: llaves en JKS | `entorno` por defecto y `keystore` como alternativa | Sí (D20/D40); falta corregir el texto del SAD (ARQ-25) |
| 9 | §2.4: "El Repositorio de Hallazgos y Versiones solo admite inserciones y consultas" | `VersionHallazgoRepository extends JpaRepository` (hereda `delete*`) | No |
| 10 | SD-09: Fábrica de Atributos; los hallazgos conservan su versión de plantilla | Sin fábrica; `plantilla_version` siempre NULL | No |
| 11 | §2.5: el Adaptador de Mensajería implementa `CanalEventos` | Clase concreta sin interfaz | No (menor) |

---

## 7. Hallazgos

| ID | Sev. | Archivo:línea | Problema | Por qué importa | Cómo corregirlo | Esf. |
|---|---|---|---|---|---|---|
| **ARQ-R-1** | **Crítico** | `consultapublica/service/FotosEspeciesService.java:45-60`; `infrastructure/FotosCientificoHttp.java:91-104`; `cientifico/registro/controller/FotoPublicaController.java:33-34`; `registro/service/EvidenciaService.java:144-155`; `FotoPublica.java:29-30` | Cada fallo de caché de una foto pública es una petición síncrona al científico. El científico consulta `hallazgo_evidencia` en la base de escritura, lee el original (hasta 9 MB) y lo decodifica y recodifica con `ImageIO` **en cada petición**. La caché de consulta-publica no tiene single-flight (la descarga, línea 54, queda fuera del `synchronized`), es por réplica (120 entradas) y acepta cualquier nombre que pase la regex. No hay circuit breaker; los timeouts son de 2 s + 5 s. No hay límite de concurrencia en ninguno de los dos lados | Es exactamente "la consulta viral": se anuncia una especie nueva, su foto está fría y llegan miles de visitas en minutos. Las N peticiones simultáneas se convierten en N decodificaciones (una foto de 24 MP ocupa unos 70 MB de heap) en un científico con `-Xmx512m`. Eso puede tumbar la carga de hallazgos (ASR2) y, con hilos bloqueados hasta 7 s, también la consulta pública (ASR1). Rompe 03 §J1 regla 2 y SAD §4.7 sin ADR. Con nombres aleatorios se esquiva la caché | (1) **Inmediato:** generar la versión reducida **una vez**, al marcar la foto como pública (`PUT …/publica`), y guardarla en disco. Que el endpoint solo sirva bytes ya hechos. (2) Single-flight en consulta-publica (`ConcurrentHashMap<String, CompletableFuture>` o Caffeine `AsyncLoadingCache`) y un semáforo pequeño (2-4) en el cliente y en el científico. Timeouts de 300 ms / 1 s y circuit breaker (reutilizar la idea de D52). (3) **Lo correcto en esta arquitectura:** un evento `evidencia.publicada` y un worker de consulta-publica que copie la miniatura a su lado de lectura (volumen o tabla en `mysql-lectura`). Así el tráfico público nunca toca el científico. Registrar el ADR que actualice la regla 2 y corrida k6 de la portada con caché fría | M |
| **ARQ-R-2** | Alto | `pruebas-carga/resultados/asr1-*.json` (parámetros sin `replicas`/`commit`/`red_k6`); `pruebas-carga/k6/registro-hallazgo.js:1` | No hay ninguna medición válida según D42 para ASR1, ASR2, ASR3 ni ASR4. El script de registro sigue marcado "NO MEDIBLE" aunque HU-01 existe desde el PR #14. Ningún k6 recorre `/api/publica/especies` ni `/foto` | Los 5 ASR en Parcial se resuelven con mediciones, no con código. Sin ellas, la sustentación defiende ASR1-4 solo de forma cualitativa | Completar `registro-hallazgo.js` (registro + evidencia de 1-2 MB) y correr K1-K8 de `docs/revision/asr-rnf.md` §7 en una sesión sobre `develop` limpio. Agregar un escenario "portada" (especies + 12 fotos) | M |
| **ARQ-R-3** | Alto | `registro/service/HallazgoService.java:84-92`; `FichaPlantillaService.java:95` (sin llamador en producción); `registro/controller/HallazgoResponse.java:27` | (a) Los `atributos` se guardan tal como llegan: no se validan contra la plantilla vigente y `plantillaVersion` queda en `null`. (b) Sus valores no pasan por `ValidadorTextoPublico`, así que una "ruta GPS", que el propio enunciado pone como ejemplo de atributo, queda en claro en `hallazgo.atributos` y se devuelve a auditor y administrador | (a) Incumple SD-09 y HU-02 c.2: la flexibilidad existe, pero sin control. (b) Abre un camino para persistir coordenadas exactas en texto plano (RNF9/ASR6) y mostrarlas a roles sin permiso (RF12) | Llamar a `validarValores` en `registrar`/`reclasificar` y guardar la versión de plantilla. Pasar los valores TEXTO por el detector de coordenadas. Para datos geográficos, un `TipoAtributo` GEO que viaje en el evento de dominio y lo cifre `ubicacion` | M |
| **ARQ-R-4** | Alto | `registro/infrastructure/PublicadorCambiosHallazgo.java:37-45`; `reportes/infrastructure/ColaSolicitudesReporte.java:48-55` | Sin outbox. Si el broker falla después del commit, el evento se pierde y solo queda un log de error. No hay herramienta para republicar ni para reconciliar | Las bases de lectura y la analítica divergen en silencio de la de escritura. Es el único mecanismo de sincronización entre bases (punto 5 del alcance) | Tabla `evento_saliente` en la misma transacción + relay `@Scheduled` que publique y marque. Como mínimo, un endpoint de administrador que republique `hallazgo.*` desde la última `VersionHallazgo` | M |
| **ARQ-R-5** | Medio | `consultapublica/config/MonitorCargaFilter.java:19,28-30` | El monitor de degradación solo mide `/api/publica/fichas`. Desde D65 la portada usa `/api/publica/especies` (con `GROUP BY`) y `/foto` | Un pico sobre el catálogo no activa la degradación automática, que es la táctica de ASR1 | Medir `/api/publica/**`. Que la estrategia degradada del catálogo omita familias y fotos | S |
| **ARQ-R-6** | Medio | `consultapublica/service/InvalidadorCacheFichas.java:35-39` | Cada evento `hallazgo.*` vacía por completo las cachés `busquedas`, `especies` y `familias` | Con carga científica simultánea (el escenario de ASR4), la tasa de aciertos de los listados cae casi a 0 y toda la lectura va a MySQL | Dejar que los listados venzan por TTL (60 s, consistencia eventual ya aceptada en D25), o usar una clave con un contador de generación y debounce. Medir con K5 | S |
| **ARQ-R-7** | Medio | `infra/mysql/transaccional/01-esquemas-y-usuarios.sh:25`; `registro/domain/VersionHallazgoRepository.java:10-13`; `VersionHallazgo.java:24` | En local (el entorno de la demo) el usuario de la app tiene `ALL PRIVILEGES`, así que el append-only solo rige en Aiven. El repositorio del historial hereda `delete*`, aunque su Javadoc dice lo contrario. El Javadoc también menciona triggers que se retiraron en 2998f84 | En la sustentación, un intento de UPDATE/DELETE en local tendría éxito | Crear en local un usuario de app con el mismo GRANT de D60 (y migrar con root). Declarar `Repository<VersionHallazgo, UUID>` con solo `save`/`find*`. Corregir el Javadoc | S |
| **ARQ-R-8** | Medio | `registro/service/ConstructorSnapshotPublico.java:33-52` (reusado por el historial en `HallazgoService.java:98,166`); `HistorialService.java:60-71` | El snapshot del historial es el **público**: no tiene `atributos`, `categoriaUicn` ni `investigador`. `comparar` no muestra cambios en los atributos | "Saber qué se creía en enero" incluye los atributos (ADN, temperatura). Hoy solo se reconstruyen encadenando los textos de la auditoría | Un snapshot de historial propio y completo (sin coordenadas), separado del que se publica | S |
| **ARQ-R-9** | Medio | Despliegue (no hay ADR en 03) | Gateway sin réplicas ni balanceador, sin TLS, sin bulkhead y sin reinicio automático. Siguen sin ADR desde el 08-11 (desviaciones 3-6 de §6) | El evaluador compara con SAD §5.1/§5.2 | Un ADR que lo acepte para el prototipo (D34) o una corrida K9 con 2 gateways detrás de NGINX | S |
| **ARQ-R-10** | Medio | `reportes/service/ConsultasReportesService.java:28-41,59-63`; `ReportesController.java:34,44,56,70` | `/api/reportes/resumen`, `/hallazgos` y `/exportar/*` son síncronos y cargan **todas** las filas filtradas en memoria, sin paginar | "Reportes que ahogan": con millones de filas, OOM en reportes. En Aiven, además, carga el servidor que comparte con la consulta pública (D39) | Agregar en SQL, paginar `/hallazgos` y mandar las exportaciones grandes por la cola de solicitudes | M |
| **ARQ-R-11** | Medio | `web-publica/src/api/especies.js:7,57`; `api-gateway/.../application.yml:92` | Una vista de la portada son 14 peticiones (listado, familias y 12 fotos) contra 120/min por IP: unas 8 vistas por minuto por IP | En un pico real, los visitantes detrás de la NAT de una universidad reciben 429, que D42 cuenta como fallo de ASR1 | Contador separado (o exento) para las fotos, con `Cache-Control: immutable` y una URL versionada. Medir en K9 a través del gateway | S |
| **ARQ-R-12** | Bajo | `registro/service/TraductorErroresDeUbicacion.java:21-22,30-53` | Registro depende de ubicación por nombres de clase y reflexión | Si se renombra la excepción, se rompe en tiempo de ejecución y ArchUnit no lo detecta | Excepción de contrato en `eventos.domain` (con `campo`) que lance ubicación | S |
| **ARQ-R-13** | Bajo | consulta-publica `service/*` → `infrastructure/*` (4 clases); `equipos/service/EquipoTrabajoService.java:7` | `service` depende de adaptadores concretos | Contradice SAD §7.1 (las dependencias apuntan hacia `domain`) | Puertos en `domain` o anotarlo como excepción en 03 | S |
| **ARQ-R-14** | Bajo | `FichaPlantillaService.java:147-155`; nombres de §5 | Faltan la Fábrica de Atributos (SD-09) y `CanalEventos`; varios nombres del SAD no existen en el código | Preguntas previsibles en la sustentación | Tabla de equivalencias (§5) en 10 o en el SAD; quitar la fábrica de SD-09 o implementarla | S |
| **ARQ-R-15** | Bajo | `reportes/domain/SolicitudReporte.java:64-71`; `SolicitudesReporteService.java:103-108`; `ColaSolicitudesReporte.java:52-55` | Si el proceso muere a mitad de un reporte, la solicitud queda en EN_PROCESO para siempre: al reentregarse, `iniciar` devuelve `false`. Si falla el envío a la cola, queda PENDIENTE sin reencolar | Reportes que nunca terminan | Tarea programada que reencole PENDIENTE o EN_PROCESO con más de N minutos | S |
| **ARQ-R-16** | Bajo | `equipos/infrastructure/GatewayCustodioAdapter.java:18-21` | `RestClient` sin timeouts ni circuit breaker; un servicio de dominio depende de la frontera | Si el gateway se cuelga, también se cuelga equipos | Timeouts (1-2 s) y circuito; o pedir los datos del custodio a Keycloak por una cuenta de servicio | S |

---

## 8. Los desafíos del enunciado: ¿los resuelve el código?

| Desafío | Respuesta |
|---|---|
| **El regreso del investigador** (fichas flexibles, equipos asociados al responsable) | **En parte.** Atributos en JSON con plantillas versionadas por especie (`FichaPlantillaService.java:52-72`), evidencias de foto y audio sin EXIF (D62) y equipos con custodio investigador activo (HU-04). Faltan: validar contra la plantilla y guardar su versión (ARQ-R-3). Además, la "ruta GPS" del ejemplo quedaría en claro |
| **La consulta viral x60** | **Diseño sí, evidencia no, y con un retroceso.** Servicio y base propios, Redis tolerante, degradación automática y límite por IP. Pero D65 lleva el pico hasta el científico (ARQ-R-1), el monitor no ve la portada nueva (ARQ-R-5) y no hay una corrida que cumpla D42 (ARQ-R-2) |
| **La ciencia se corrige** | **Sí.** Versión n+1 con justificación obligatoria (Builder), auditoría campo a campo con autor, comparación entre versiones y GRANT sin UPDATE/DELETE probado con MySQL real. Salvedades: el snapshot no incluye atributos (ARQ-R-8) y el GRANT no rige en local (ARQ-R-7) |
| **El corredor biológico** | **Sí.** AES-256-GCM, una sola política (`PoliticaAccesoUbicacion`) con la generalizada por defecto, doble barrera garantizada con ArchUnit, eventos sin coordenadas y EXIF eliminado. Brecha nueva: los atributos (ARQ-R-3) |
| **Plataforma abierta** | **Sí.** API externa `/api/v1/externa/fichas/**` con API Key, 60/min configurable y medido con k6. Destinos internos por variable |
| **Reportes que ahogan** | **Sí en lo estructural.** Servicio, base analítica alimentada por eventos, cola, worker, cruce agregado en SQL y nunca toca `cientifico`. Faltan la medición del Δ ≤ 10 %, los endpoints síncronos sin límite (ARQ-R-10) y, en Aiven, el servidor compartido con la lectura (D39) |

---

## 9. Bien hecho

- **Las tácticas de los 9 ASR tienen código** y CI verde con 5 servicios + Bruno + 2 webs en 3 motores. En 11 días se cerraron los tres "No cumple" de la revisión anterior (ASR3, ASR5, ASR7).
- **ASR7 probado como debe:** `MigracionesRegistroTest` crea un usuario con los permisos de producción y comprueba contra MySQL 8.4 que UPDATE/DELETE fallan sobre el historial y no sobre `hallazgo`. `HistorialAppendOnlyScriptTest` rompe el build si una tabla nueva no está en el script (D60). Se descartaron los triggers después de comprobar el ERROR 1419.
- **Módulos limpios:** registro y ubicación se integran solo por eventos de dominio (D53). El orden es correcto: ubicación en la misma transacción (y si el cifrado falla, se revierte todo) y el broker después del commit.
- **Reporte asíncrono bien resuelto:** el mensaje lleva solo el id, el cruce se hace con `GROUP BY` en SQL (nunca millones de filas en memoria), el procesamiento es idempotente frente a reentregas y hay un reporte mensual programado.
- **Proyecciones idempotentes en los dos consumidores** (versión mayor + `evento_procesado`) con DLQ y defensa contra coordenadas en los eventos.
- **Evidencias** reconocidas por contenido, sin EXIF y deduplicadas por SHA-256 (D62). Es una lectura correcta de RNF9 que el SAD no había previsto.
- **Disciplina de ADR:** D59-D65 incluyen alternativas descartadas y consecuencias. Las capas mejoraron (ARQ-18 cerrado y `layeredArchitecture` en consulta-publica).

---

## 10. Calificación de arquitectura: **7,0 / 10**

**A favor (+):** el estilo del SAD está implementado de verdad, no solo dibujado: servicios de grano grueso, gateway propio con circuit breaker y límites, broker con DLQ, 4 bases separadas y CQRS real. Los cinco patrones del SAD §6 existen y están conectados. Las garantías críticas (cifrado, autorización centralizada, append-only, módulos aislados) las vigilan pruebas automatizadas (ArchUnit, Testcontainers con MySQL y RabbitMQ reales).

**En contra (−):**
- −1,5: D65 rompe la invariante central del enunciado ("la consulta pública jamás compite con la carga científica") sin ADR ni medición (ARQ-R-1).
- −1,0: ninguno de los ASR de disponibilidad y rendimiento (1-4) tiene una medición válida según D42 (ARQ-R-2).
- −0,5: ASR5 a medio conectar (ARQ-R-3) y sin outbox para la sincronización de las bases (ARQ-R-4).

Si se cierran ARQ-R-1 (al menos el paso 1 y el single-flight), ARQ-R-2 y ARQ-R-3, la calificación sube a 8,5-9.
