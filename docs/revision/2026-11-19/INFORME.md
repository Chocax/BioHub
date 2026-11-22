# Informe consolidado: revisión del 2026-11-19

**Rama:** `develop` (contenido de `8d23981`, PR #17; tras quitar la coautoría de Claude de los mensajes el HEAD es `14f140c`, mismo árbol). **Autor:** Tech Lead. **Modo:** solo lectura.
**Fuentes:** `arquitectura-asr.md`, `requisitos.md`, `backend.md`, `base-de-datos.md`, `frontend.md`, `seguridad.md`, `devops.md` (esta carpeta). `calidad.md` (QA: build, tests y app viva ejecutados).
**Línea base:** `docs/revision/INFORME.md` (2026-11-08, HEAD `6a5495a`). Desde entonces entraron 96 commits (PR #8 a #17).

## 1. Resumen ejecutivo

El proyecto dio un salto grande desde el 08-11: **ya no queda ningún requisito sin código**. Registro, historial, equipos, reportes asíncronos, evidencias y las dos webs están implementados, y el CI de `develop` está verde (5 módulos Java, 2 webs, Bruno E2E).

| Grupo | Cumple | Parcial | No cumple | 08-11 (C/P/N) |
|---|---:|---:|---:|---|
| RF (19) | 16 | 3 | 0 | 7 / 1 / 11 |
| HU (13) | 8 | 5 | 0 | 1 / 6 / 6 |
| Criterios de aceptación (38) | 32 | 6 | 0 | 18 / – / – |
| RNF (13) | 4 | 9 | 0 | 3 / 6 / 4 |
| ASR (9) | 4 | 5 | 0 | 3 / 3 / 3 |

Lo que falta ya no es "construir", sino **cerrar huecos de robustez y medir**: un retroceso arquitectónico (fotos públicas D65), una fuga de GPS en evidencias, validaciones incompletas, ausencia de outbox y las mediciones k6 de ASR1–ASR4.

## 2. Calificación por área

| Área | Nota | Justificación |
|---|---:|---|
| Requisitos funcionales | 8,0 | 92 % de RF y criterios. Faltan validación de plantilla, archivos de datos (RF1) y custodio obligatorio en el alta (RF6). |
| Arquitectura / ASR / patrones | 7,0 | Estilo, CQRS y los 5 patrones del SAD reales y probados. Resta D65 (rompe aislamiento), sin mediciones D42 y ASR5 a medias. |
| Backend | 7,0 | Buen contrato, historial y consumidor idempotente. 5 Altos de consistencia (evento perdido, NPE, validaciones). |
| Base de datos | 7,5 | Escritura/lectura/analítica/gateway separadas y verificadas. Sin outbox/reproyección; append-only solo en Aiven. |
| Frontend | 7,5 | Token en memoria, PKCE, lista blanca pública. Faltan tests de pantallas nuevas y CSP en la interna. |
| Seguridad | 7,0 · **Rechazado** | 1 Alto (GPS en evidencias). Sin secretos, sin inyección SQL/XSS/SSRF, 0 vulnerabilidades npm. |
| DevOps | 6,5 | CI sólido y verde. Sin script Linux, README desfasado, sin artefactos de despliegue de la Entrega 3. |
| Calidad (QA) | 8,5 · **Aprobado** | `mvn verify` 1.173 tests, 0 fallos; webs 98 + 34 unitarios y 78 + 38 E2E; Bruno 127/127 con semilla; flujo real 63/64 (la falla era del script de QA). Sin bugs Críticos ni Altos. |

## 3. Bases de escritura y lectura (pedido explícito)

| Servicio | Base | Rol |
|---|---|---|
| servicio-cientifico | mysql-transaccional :3307, esquema `cientifico` | Escritura |
| servicio-equipos | mysql-transaccional :3307, esquema `equipos` | Escritura |
| servicio-consulta-publica | mysql-lectura :3308 + Redis | Lectura |
| servicio-reportes | mysql-analitica :3309 | Analítica |
| api-gateway | mysql-gateway :3310 | API keys y límites |

Verificado con consultas reales: instancias distintas, un usuario por servicio, ningún esquema visible desde otra instancia, `ficha_publica` sin columnas de coordenadas, catálogo de departamentos idéntico en escritura y lectura, colas durables con DLQ. La proyección es idempotente y tolera desorden. **Riesgos:** no hay outbox ni reproyección (DB-R-1) y en local la app tiene `ALL PRIVILEGES` (DB-R-2). **Prueba viva (QA):** se registró un hallazgo por el gateway y quedó en `cientifico.hallazgo` (coordenadas cifradas en `ubicacion_hallazgo`), apareció en `consulta_publica.ficha_publica` en menos de 1 s sin coordenadas, la reclasificación llegó como versión 2 con la caché Redis invalidada, y `mysql-analitica` recibió el evento.

## 4. Desafíos del enunciado

| Desafío | Estado |
|---|---|
| El regreso del investigador (fichas flexibles, equipos) | A medias: fichas sin validar contra plantilla; alta de equipo sin custodio. |
| La consulta viral (x60, sin competir con la carga) | Diseño correcto (lectura separada + caché), **pero D65 lo rompe** y no hay medición D42. |
| La ciencia se corrige (historial completo) | Resuelto en estructura; append-only físico solo en Aiven; snapshot sin atributos. |
| El corredor biológico (coordenadas) | Resuelto en rutas públicas; **fuga en metadatos de evidencias** (SEC-R-1). |
| Plataforma abierta (gateway, rate limit) | Resuelto (ASR8, ASR9 Cumple). Gateway sigue como punto único de falla sin ADR. |
| Reportes que ahogan al sistema | Resuelto en estructura (asíncrono + base analítica); falta medir RNF3 (≤10 %). |

## 5. Top 10 priorizado (hallazgos duplicados unificados)

| # | Qué | IDs | Sev. | Esf. |
|---|---|---|---|---|
| 1 | Fotos públicas D65: consulta-pública llama al científico y este decodifica originales de hasta 9 MB desde la base de escritura, sin circuit breaker. Pre-generar miniaturas al publicar y servirlas desde el lado de lectura | ARQ-R-1, DB (Medio) | Crítico | M |
| 2 | Evidencias conservan GPS: audio sin limpiar (GUANO/`Loc Position` en WAV), segunda imagen MPF y trailer Motion Photo en JPEG | SEC-R-1 | Alto | M |
| 3 | Validadores de coordenadas en texto inconsistentes: el hallazgo se guarda, el evento falla, el cliente recibe 500 y al reintentar se duplica. Unificar en un solo detector | BE-R-1, SEC-R-2 | Alto | S |
| 4 | Atributos flexibles sin validar contra la plantilla (`validarValores` sin llamadores, `plantillaVersion` null) | BE-R-4, ARQ-R-3, REQ HU-02 | Alto | M |
| 5 | Sin outbox ni reproyección: un evento perdido deja lectura y analítica desfasadas para siempre. Primero un endpoint de republicación, luego outbox | BE-R-2, DB-R-1, ARQ-R-4 | Alto | S + M |
| 6 | Append-only solo en Aiven; en local la app tiene `ALL PRIVILEGES` y los repositorios heredan borrados | DB-R-2, ARQ-R-7, REQ RNF10 | Alto | M |
| 7 | Posible NPE en `Map.copyOf` con nulos en `/versiones` y `/comparar` (QA las vio responder bien con datos completos; falta probar con campos vacíos) | BE-R-3 | Alto | S |
| 8 | Departamento en texto libre: reportes parte "05" y "Antioquia"; `region` siempre NULL | BE-R-5, DB (Medio) | Alto | S |
| 9 | Mediciones k6 de ASR1–ASR4 y RNF3 con metadatos D42, incluyendo la portada nueva | ARQ-R-2, REQ HU-06/09 | Alto | M |
| 10 | Entrega: script de arranque para Linux/macOS, README y `.env.example` al día, artefactos de despliegue | OPS-R-1, 2, 3, 6, 7 | Alto | M |

**Medios relevantes:** `nombreCientifico` acepta y publica `<script>` (QA-R-1; React lo escapa, pero se debe rechazar en origen); reclasificación concurrente da 500 en vez de 409 (sin `@Version`); asignación concurrente de equipos; inyección de fórmulas en CSV; solicitudes de reporte huérfanas; consultas "más recientes por especie" sin índice; 7 de 8 pantallas nuevas de `web-interna` sin test; cualquier investigador puede reclasificar hallazgos ajenos.

**Alcance de más (el enunciado pide no inventar):** catálogo y fotos por especie (D65, estaba en Won't), carrusel de pinturas, estados extra de equipos, endpoints síncronos de reportes sin pantalla, endpoint de prueba de eventos expuesto.

## 6. Plan por etapas

- **Rápidas (1–2 días):** #3, #7, #8; endpoint de republicación (#5 parte S); CSV injection; neutralizar o apagar el endpoint de prueba de eventos.
- **Importantes (antes de la sustentación):** #1 (o retirar D65 y documentarlo en un ADR), #2, #4, #6, #9; tests de pantallas de `web-interna`.
- **Largo plazo:** outbox, ADR del gateway como punto único de falla, despliegue completo, lint y auditoría de dependencias Java en CI.

## 7. Fuera del código (esta sesión)

- Se quitó la coautoría de Claude de los 19 commits que la tenían, en `develop` y 5 ramas `feature/*`; el contenido no cambió. Respaldo en bundle fuera del repositorio. La protección de `develop` se restauró y se verificó.
- El equipo debe volver a clonar o hacer `git reset --hard origin/<rama>`.
