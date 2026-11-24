# Revisión backend, 2026-11-19

**Rama:** `develop` (HEAD `8d23981`). **Modo:** solo lectura (sin `mvn`, sin servicios; los tests se evaluaron leyéndolos, no ejecutándolos).
**Alcance leído:** `servicio-cientifico` (registro: hallazgos, historial, evidencias, plantillas, galería, foto pública; ubicación: solo el cruce con registro), `servicio-equipos`, `servicio-reportes`, `servicio-consulta-publica` (consumidor, proyección, catálogo por especie, foto D65) y, del `api-gateway`, lo que cambió desde `6a5495a` (teselas) más `ProxyService` y `SecurityConfig`.
**Rutas base:** `servicio-cientifico/src/main/java/co/edu/unbosque/biohub/cientifico` (abreviado `CIE/`), `servicio-reportes/.../reportes` (`REP/`), `servicio-equipos/.../equipos` (`EQU/`), `servicio-consulta-publica/.../consultapublica` (`PUB/`).

## Resumen

| Severidad | Cantidad |
|---|---|
| Crítico | 0 |
| Alto | 5 |
| Medio | 12 |
| Bajo | 7 |
| **Total** | **24** |

Los hallazgos "probables" (BE-R-3, BE-R-18) salen de leer el código y la semántica de JDK/Hibernate; no se ejecutaron. Conviene confirmarlos con un test de integración antes de corregir.

---

## Hallazgos Altos

### BE-R-1 (Alto) Hallazgo guardado, sin evento y con respuesta 500 cuando los dos detectores de coordenadas no coinciden
- **Archivo:** `CIE/registro/infrastructure/PublicadorCambiosHallazgo.java:37-41` y `CIE/eventos/service/PublicadorEventos.java:70`; reglas en `CIE/registro/service/ValidadorTextoPublico.java` frente a `CIE/eventos/service/DetectorCoordenadas.java`.
- **Problema:** registro valida los textos con `ValidadorTextoPublico` (3 patrones), pero el publicador, ya después del commit, aplica `DetectorCoordenadas`, que es más agresivo: `CLAVE_Y_NUMERO` (`long 12`, `lat: 5`), decimales con coma, `5,5°N`, grado más minutos con comillas tipográficas. Un texto legítimo como "long. 12 cm" o "lat 3" pasa la validación de registro, se confirma la transacción, y `PublicadorEventos.publicar` lanza `IllegalArgumentException`. El listener solo captura `EventoNoPublicadoException`, así que la excepción sube desde `afterCommit`: el cliente recibe 500 con el hallazgo ya guardado, y la ficha pública y el reporte nunca se enteran.
- **Por qué importa:** rompe la consistencia entre la base de escritura y las de lectura, y el cliente que reintenta duplica el hallazgo (el alta no es idempotente).
- **Corrección:** (a) usar un único detector para validar y para publicar (que `ConstructorSnapshotPublico.construir` llame al mismo que el publicador, y así falle antes del commit con 400); (b) en el listener capturar `RuntimeException` y registrar el error; nunca dejar escapar excepciones desde AFTER_COMMIT. Ver BE-R-23 sobre la duplicación.
- **Esfuerzo:** S.

### BE-R-2 (Alto) Sin outbox: un fallo del broker pierde el evento para siempre
- **Archivo:** `CIE/registro/infrastructure/PublicadorCambiosHallazgo.java:37-46`; contrato 04 §14.6 lo reconoce como pendiente.
- **Problema:** el evento se publica en AFTER_COMMIT. Si el broker no confirma en 5 s, o el proceso se cae entre el commit y el envío, queda solo una línea de log ("hay que republicarlo") y no existe mecanismo ni endpoint para republicar. Las fichas públicas (`ficha_publica`) y la base analítica de reportes quedan desfasadas sin que nada lo detecte. La idempotencia por versión en los consumidores permitiría reenviar sin riesgo, pero nadie reenvía.
- **Por qué importa:** el requisito central (bases de lectura sincronizadas por eventos) falla en silencio en el escenario de fallo más típico (RabbitMQ caído un rato).
- **Corrección:** mínimo viable: tabla `evento_pendiente` escrita en la misma transacción que el hallazgo (el snapshot ya se construye ahí) y un `@Scheduled` que publica y marca enviados. Alternativa barata: job que republique los hallazgos con `version` mayor a la última publicada.
- **Esfuerzo:** M.

### BE-R-3 (Alto, probable) `Map.copyOf` con valores nulos lanza NPE al leer el historial y al reclasificar
- **Archivo:** `CIE/registro/domain/VersionHallazgo.java:96` (`getSnapshot`), `CIE/registro/domain/Hallazgo.java:210` (`getAtributos`).
- **Problema:** `Map.copyOf` rechaza valores nulos. El snapshot guarda `nombreComun` y `descripcion` como `null` cuando no se informan (`ConstructorSnapshotPublico`), y MySQL/Hibernate devuelven esas claves con valor `null`. Entonces `GET /hallazgos/{id}/versiones` y `/comparar` fallan con 500 para cualquier hallazgo sin nombre común o sin descripción (los tests de historial usan mocks y mapas sin nulos). Del mismo modo, `reclasificar` llama `copiaDeReferencia(hallazgo)` → `getAtributos()`, que falla si el cliente envió `"atributos": {"x": null}`.
- **Por qué importa:** rompe HU-05 (auditoría) en el caso más común (campos opcionales vacíos) y deja un 500 provocable por entrada del usuario.
- **Corrección:** devolver `Collections.unmodifiableMap(new LinkedHashMap<>(m))` (admite nulos y conserva el orden), y rechazar valores nulos en `atributos` al validar (BE-R-4). Añadir un test de integración MySQL: registrar sin `nombreComun`, luego `GET /versiones`.
- **Esfuerzo:** S.

### BE-R-4 (Alto) Los `atributos` del hallazgo no se validan contra la plantilla (HU-02 c.2); `validarValores` es código muerto
- **Archivo:** `CIE/registro/service/HallazgoService.java:89-92, 159-162` (nadie llama a `FichaPlantillaService.validarValores`, `FichaPlantillaService.java:233`); `Hallazgo.plantillaVersion` siempre `null`.
- **Problema:** `registrar` y `reclasificar` guardan el mapa `atributos` tal como llega: sin comprobar obligatorios, tipos, claves desconocidas, número de claves, tamaño ni profundidad. La validación existe y tiene tests, pero solo se ejercita en `FichaPlantillaServiceTest`. La versión de plantilla con que se capturó tampoco se guarda, así que no se puede saber contra qué plantilla se validó un hallazgo viejo.
- **Por qué importa:** HU-02 pide que la ficha se valide; hoy depende del front. Un cliente directo (o un front con bug) llena la columna JSON con cualquier cosa, hasta ~10 MB de gateway.
- **Corrección:** llamar `plantillas.validarValores(nombreCientifico, atributos)` dentro de `registrar` y `reclasificar` (si la política es "sin plantilla = no se exige", mantener ese comportamiento pero limitar claves/tamaño), guardar `plantillaVersion`, y rechazar valores nulos/anidados.
- **Esfuerzo:** M.

### BE-R-5 (Alto) El departamento se guarda y se publica tal como lo escribió el usuario, no el nombre oficial
- **Archivo:** `CIE/registro/service/HallazgoService.java:89-92` (`comando.departamento().strip()`) y `:251`; `CIE/ubicacion/service/CatalogoDepartamentos.java` (resuelve "15", "boyaca", "BOYACÁ" a la misma fila); `REP/service/ProyectorHallazgosService.java:151` y `REP/domain/HallazgoReporteRepository.java:260-266`.
- **Problema:** ubicación acepta cualquier variante (código DANE, sin tilde, mayúsculas) y la normaliza para su propia tabla, pero registro conserva y publica el texto crudo. `consulta-publica` vuelve a resolverlo con su catálogo, pero `servicio-reportes` no: guarda `"15"`, `"boyaca"` o `"Boyacá"` como valores distintos. `GROUP BY` con colación `ai_ci` junta tildes y mayúsculas pero nunca `"15"` con `"Boyacá"`, y `CalculadorIndicePresion.clave()` compara en Java con `toLowerCase()` (sensible a tildes) contra lo que devolvió SQL: los promedios previos pueden no emparejar y el crecimiento sale en 1 por error.
- **Por qué importa:** el índice de presión (RF15/RF16) y las zonas en alerta se calculan sobre datos partidos por departamento; además el historial y la galería muestran textos inconsistentes.
- **Corrección:** que ubicación devuelva el nombre canónico (por ejemplo `ResultadoUbicacion` con el departamento resuelto, o un puerto/evento de vuelta) y que registro lo use antes de guardar y de armar el snapshot; defensa adicional: normalizar con el catálogo también en el proyector de reportes.
- **Esfuerzo:** M.

---

## Hallazgos Medios

### BE-R-6 (Medio) El manejador de errores de registro es incompleto; la concurrencia sobre un mismo hallazgo da 500
- **Archivo:** `CIE/registro/controller/RegistroExceptionHandler.java` (sin `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `DataIntegrityViolationException` ni catch-all); `CIE/registro/domain/Hallazgo.java:27-31` (sin `@Version`); `HallazgoService.java:135-175`.
- **Problema:** JSON malformado, fecha con formato malo, `categoriaUicn` desconocida, `id` que no es UUID o `comparar` sin `desde` no responden `problem+json` con `codigo` (salen con el formato por defecto de Boot), contra el contrato §2 (`PARAMETRO_INVALIDO`, `ERROR_INTERNO`). Ubicación sí tiene `inesperado`, `DataAccessException` y reenvío de `ErrorResponse`. Además, dos reclasificaciones simultáneas calculan la misma versión nueva; la segunda pisa el `UPDATE` de `hallazgo` y falla recién en el commit por el UNIQUE `(hallazgo_id, version)` de `hallazgo_version`. La integridad se conserva (el historial es correcto) pero el cliente recibe 500 en vez de 409.
- **Corrección:** copiar el patrón de `UbicacionExceptionHandler` (400 para los errores de lectura, 409 `CONFLICTO` para `DataIntegrityViolationException`/`ObjectOptimisticLockingFailureException`, 500 genérico) y añadir `@Version` o `@Lock(PESSIMISTIC_WRITE)` en `buscarVigente`.
- **Esfuerzo:** S.

### BE-R-7 (Medio) La conexión de base de datos queda tomada mientras se espera la confirmación del broker
- **Archivo:** `CIE/registro/infrastructure/PublicadorCambiosHallazgo.java:37`, `CIE/eventos/service/PublicadorEventos.java:79-86` (`waitForConfirmsOrDie(5s)`).
- **Problema:** en `AFTER_COMMIT` la conexión JDBC todavía no se devolvió al pool (Spring la libera después de los callbacks). Con el broker lento, cada alta retiene una conexión hasta 5 s; unas pocas docenas de altas concurrentes agotan el pool de Hikari (10 por defecto) y el servicio deja de responder, incluso lecturas.
- **Corrección:** con el outbox de BE-R-2 el problema desaparece (se publica en otro hilo). Mientras tanto, publicar con `@Async` o con un ejecutor propio, o bajar el timeout de confirmación en este camino.
- **Esfuerzo:** S (con outbox, M).

### BE-R-8 (Medio) Solicitudes de reporte que quedan huérfanas; el reporte programado no se reintenta
- **Archivo:** `REP/infrastructure/ColaSolicitudesReporte.java:44-51`, `REP/service/SolicitudesReporteService.java:61-68, 91-95`, `REP/domain/SolicitudReporte.java:64`.
- **Problema:** (a) si el encolado AFTER_COMMIT falla, o el servicio se cae después del commit, la solicitud queda `PENDIENTE` para siempre; (b) si se cae durante la generación queda `EN_PROCESO` y la redelivery de RabbitMQ no hace nada porque `iniciar()` solo acepta `PENDIENTE`; (c) `solicitarMesAnteriorSiFalta` busca solo por periodo+origen: si la del mes quedó `FALLIDO` o atascada no se reintenta hasta el mes siguiente, y con dos instancias el check-then-insert no es atómico (no hay UNIQUE en `(periodo, origen)` para PROGRAMADO). El frontend consulta el estado "hasta LISTO o FALLIDO" y se queda esperando.
- **Corrección:** un `@Scheduled` barredor que re-encole `PENDIENTE` de más de N minutos y marque `FALLIDO` las `EN_PROCESO` de más de M minutos; que el chequeo mensual ignore las `FALLIDO`; `UNIQUE (periodo)` filtrado por origen, o un lock (ShedLock) si hay más de una instancia.
- **Esfuerzo:** M.

### BE-R-9 (Medio) El consumidor de reportes no tiene las defensas del de consulta-publica
- **Archivo:** `REP/eventos/service/ConsumidorEventos.java:55-62`, `REP/service/ProyectorHallazgosService.java:154-157`.
- **Problema:** no valida `origen` (D24) ni el `user_id` AMQP (SEC-J-01), no busca coordenadas en el texto libre y su única comprobación de coordenadas es de tres claves de primer nivel y solo para `EN_PELIGRO`. Cualquier productor con permiso en el exchange puede inyectar hallazgos falsos o coordenadas en la base analítica. Además hay un constructor público de dos argumentos que pasa `proyector = null` solo para los tests; en producción un bean mal cableado ignoraría en silencio los `hallazgo.*`.
- **Corrección:** extraer el validador de origen y detector de consulta-publica (o duplicarlo con test), quitar el constructor con `null`, y que el consumidor falle si el proyector falta.
- **Esfuerzo:** S.

### BE-R-10 (Medio) Las consultas inmediatas de reportes cargan la tabla completa en memoria
- **Archivo:** `REP/service/ConsultasReportesService.java:31, 62` (`buscarFiltrado` devuelve `List<HallazgoReporte>` sin tope), usado por `generarResumen`, `/hallazgos`, `/exportar/csv` y `/exportar/pdf`.
- **Problema:** el reporte mensual ya agrega en SQL "aunque haya millones", pero `/resumen`, `/hallazgos` y las exportaciones sin filtros materializan todas las entidades y agrupan en Java; `exportarPdf` además ejecuta la consulta dos veces. Con unas cien mil filas es lento y con más, OutOfMemoryError. El endpoint de detalle tampoco pagina.
- **Corrección:** `GROUP BY` en SQL para el resumen, paginación obligatoria en `/hallazgos`, tope de filas (o `Stream` + `StreamingResponseBody`) en el CSV, y `exportarPdf` con una sola consulta.
- **Esfuerzo:** M.

### BE-R-11 (Medio) El CSV de las exportaciones inmediatas no neutraliza fórmulas
- **Archivo:** `REP/service/ConsultasReportesService.java:151` (`escaparCsv`), frente a `SolicitudesReporteService.celda` que sí lo hace.
- **Problema:** `nombreCientifico`, `familia` y `nombreComun` son texto libre del investigador (registro solo busca coordenadas). Un valor que empiece con `=`, `+`, `-` o `@` se ejecuta como fórmula cuando el analista abre el CSV en Excel. El CSV mensual está protegido; el inmediato no.
- **Corrección:** reutilizar un único método `celda` (con el apóstrofo inicial) para ambos CSV; de paso unificar separador y formato (el inmediato usa coma y el mensual `;`).
- **Esfuerzo:** S.

### BE-R-12 (Medio) Equipos: llamada HTTP al gateway dentro de la transacción y sin tiempos de espera
- **Archivo:** `EQU/service/EquipoTrabajoService.java:43-59` (`@Transactional` en `asignar`), `EQU/infrastructure/GatewayCustodioAdapter.java:19` (`RestClient.builder().baseUrl(..).build()` sin `requestFactory`).
- **Problema:** `asignar` abre transacción, lee el equipo y llama al gateway (que a su vez llama a Keycloak) con la conexión de BD tomada. El cliente HTTP usa el `HttpClient` por defecto, sin timeouts de conexión ni lectura: un gateway colgado deja hilos y conexiones colgados indefinidamente. En `consulta-publica` (`FotosCientificoHttp`) sí se fijaron 2 s/5 s, lo que muestra que se conoce el patrón. Además, `HttpClientErrorException` de 401/403/429 del gateway se traduce como `CustodioInvalidoException` (400 "no fue posible validar"), lo que engaña: un límite de tasa no es un dato inválido.
- **Corrección:** validar el custodio antes de abrir la transacción (o con `TransactionTemplate` solo para guardar), fijar timeouts, y mapear 401/403/429/5xx a 503 `SERVICIO_NO_DISPONIBLE` o 502.
- **Esfuerzo:** S.

### BE-R-13 (Medio) Equipos: sin máquina de estados, sin historial de custodia y sin control de concurrencia
- **Archivo:** `EQU/service/EquipoTrabajoService.java:62-70`, `EQU/domain/EquipoTrabajo.java:167-182`.
- **Problema:** (a) `BAJA` no es terminal: `PUT /estado` puede devolver un equipo dado de baja a `DISPONIBLE` o `MANTENIMIENTO`; (b) `asignar` sobre un equipo ya `ASIGNADO` lo reasigna sin aviso y sin dejar rastro de quién lo tenía ("para evitar pérdidas", HU-04); (c) sin `@Version`, dos administradores concurrentes se pisan (el último gana); (d) el `UNIQUE` de `codigo_inventario` sí está, pero `registrar` hace check-then-insert y la carrera termina en 500 por falta de manejo de `DataIntegrityViolationException`. La regla "custodio activo único" sí está bien resuelta (un solo campo `custodio_id` más un `CHECK` en BD).
- **Corrección:** transición válida explícita (BAJA terminal, reasignar exige liberar o un flag explícito), tabla `equipo_custodia` append-only si se quiere trazabilidad, `@Version`, y handler para el UNIQUE (409).
- **Esfuerzo:** M.

### BE-R-14 (Medio) Foto pública: sin caché en el servicio de origen, el caché de consulta-publica se evade con nombres variables, y retirar una foto tarda ~20 minutos
- **Archivo:** `CIE/registro/service/EvidenciaService.java:144-154`, `CIE/registro/controller/FotoPublicaController.java:39`, `PUB/service/FotosEspeciesService.java:45-58`.
- **Problema:** (a) `fotoPublicaDeEspecie` lee de disco, decodifica y re-codifica la imagen en cada petición, sin caché; (b) en consulta-publica el caché es un LRU de 120 entradas por nombre y sin single-flight: un cliente anónimo que pida >120 nombres distintos (el patrón admite casi cualquier texto de letras) fuerza una llamada interna y una consulta SQL por petición, y varias peticiones simultáneas por el mismo nombre en frío lanzan todas la llamada; (c) marcar una foto como no pública no invalida nada: se sigue mostrando hasta 10 minutos en el caché del servicio más 10 en el navegador (`max-age=600` público).
- **Corrección:** caché de bytes ya reducidos en científico (clave `evidenciaId`, p. ej. Caffeine), `ConcurrentHashMap.computeIfAbsent` o Caffeine con `get(key, loader)` en consulta-publica, limitar nombres a los de especies conocidas (consultar `ficha_publica`), y publicar un evento mínimo o bajar los TTL cuando se retira una foto.
- **Esfuerzo:** M.

### BE-R-15 (Medio) La galería carga todas las filas de cada especie para quedarse con la primera
- **Archivo:** `CIE/registro/infrastructure/CatalogoEspeciesJdbc.java:58-70` (hallazgos, fichas y fotos) y `PUB/infrastructure/BusquedaEspeciesJdbc.java:82-97`.
- **Problema:** por cada página (12 a 48 especies) se ejecutan consultas `IN (...)` sin límite que traen todos los hallazgos (y todas las versiones de plantilla y todas las fotos) de esas especies, ordenados, y se descarta todo menos el primero por especie (`putIfAbsent`). El coste crece con los registros por especie, no con el tamaño de la página. En científico `contar` además hace `UNION ALL` con `ficha_plantilla` sobre todo el conjunto cada vez.
- **Corrección:** `ROW_NUMBER() OVER (PARTITION BY ...)` (MySQL 8 lo soporta; el comentario dice que se evitó por `ONLY_FULL_GROUP_BY`, pero no aplica a ventanas) o subconsulta correlacionada con `LIMIT 1`.
- **Esfuerzo:** S.

### BE-R-16 (Medio) Cualquier investigador puede reclasificar, subir evidencias y hacer pública una foto de cualquier hallazgo
- **Archivo:** `CIE/registro/controller/HallazgoController.java:65-76`, `CIE/registro/controller/EvidenciaController.java:228, 269`.
- **Problema:** los endpoints solo verifican el rol (`investigador_campo`/`cientifico_alto_rango`); no comparan `hallazgo.investigador` con el usuario del JWT. Un investigador de campo puede reclasificar el estado de conservación de un hallazgo ajeno (la historia pide "rol científico", no cualquier rol de campo) y decidir que una foto ajena salga en el portal público. El contrato no lo restringe, pero la directiva "no devolver datos de otros usuarios" y el principio de mínimo privilegio sugieren decidirlo y documentarlo.
- **Corrección:** decidir con producto: o (a) solo el autor o un `cientifico_alto_rango` puede modificar, o (b) dejarlo explícito en el contrato (ADR). Implementación: comparar en el servicio y responder 403.
- **Esfuerzo:** S.

### BE-R-17 (Medio) Lógica crítica sin tests
- **Faltan:**
  - `PublicadorCambiosHallazgo` (AFTER_COMMIT, qué pasa si el broker falla, qué pasa con una excepción distinta). No hay un test que demuestre "sin commit no hay evento" ni "commit con broker caído conserva el hallazgo". `HallazgoServiceTest` mockea el publicador.
  - Concurrencia en reclasificación (dos versiones iguales) y en alta de evidencias con el mismo SHA.
  - `HallazgoController`, `HistorialController`, `FichaPlantillaController`: no hay tests web de registro (solo `MySql*Test` de evidencias y especies); los códigos 400/404/409 y el formato `problem+json` de registro no se verifican de punta a punta.
  - `GatewayCustodioAdapter` (equipos), `ColaSolicitudesReporte`, el programador mensual, `FotosEspeciesService`, `EvidenciaService.nombreVisible`, `FotoPublica`.
  - Historial con campos nulos (ver BE-R-3) y `atributos` en el flujo de registro (BE-R-4).
- **Corrección:** priorizar tres tests: publicador (commit/rollback/broker caído), registro web (400/404/409), historial con nulos.
- **Esfuerzo:** M.

---

## Hallazgos Bajos

### BE-R-18 (Bajo, probable) `creadoEn` llega `null` en la respuesta 201 de una evidencia nueva, y los `save` con id asignado hacen un SELECT extra
- **Archivo:** `CIE/registro/domain/EvidenciaHallazgo.java:65` (`insertable=false`), `CIE/registro/service/EvidenciaService.java:90`; igual en `VersionHallazgo` y `Hallazgo`.
- **Problema:** las entidades tienen id asignado a mano y no implementan `Persistable`; `save` hace `merge` (un SELECT antes del INSERT) y la copia devuelta no trae el valor que la BD puso en `creado_en`. El 201 del contrato dice que devuelve la evidencia con `creadoEn`; en el 200 (duplicado) sí viene.
- **Corrección:** `Persistable<UUID>` con `isNew()`, y `@Generated(event = INSERT)` (o `entityManager.refresh`) para `creado_en`.
- **Esfuerzo:** S.

### BE-R-19 (Bajo) Reclasificación sin cambios, campos que no se pueden vaciar, historial sin 404
- **Archivo:** `HallazgoService.java:159-175`, `CIE/registro/service/HistorialService.java:49`.
- **Problema:** una reclasificación con valores idénticos crea una versión nueva, un evento y cero filas de auditoría; `limpiar` convierte `""` en `null` y `null` significa "no tocar", así que un campo opcional (`nombreComun`, `descripcion`) no se puede borrar; `cambiosDe` no verifica que el hallazgo exista (devuelve `[]` donde `versionesDe` da 404).
- **Corrección:** rechazar (400 o 200 sin versión) si no hay diferencias; definir cómo se vacía un campo (cadena vacía explícita); 404 coherente.
- **Esfuerzo:** S.

### BE-R-20 (Bajo) Código muerto
- `Hallazgo.anular` y `estaAnulado` (`CIE/registro/domain/Hallazgo.java:152-158`): nada anula hallazgos; no hay endpoint ni servicio. Por tanto `buscarVigente`, `buscarVigentesPorEspecie` y los `anulado_en IS NULL` de las consultas son siempre equivalentes a "todos". `HallazgoController.consultar` usa `findById` (devolvería anulados) mientras `reclasificar` usa `buscarVigente`: inconsistente.
- `HallazgoRepository.findByInvestigadorOrderByFechaRecoleccionDesc`, `EvidenciaHallazgoRepository.existsByHallazgoIdAndSha256`, `FichaPlantillaService.validarValores` (BE-R-4).
- `REP`: el campo `region` se lee de `datos` pero el contrato §7.3 no la envía, así que siempre es `null`; el CSV inmediato la imprime vacía.
- **Corrección:** eliminar lo que no se use o conectarlo (anulación, validación) y decidirlo.
- **Esfuerzo:** S.

### BE-R-21 (Bajo) Manejadores globales de `IllegalArgumentException` que devuelven el mensaje tal cual
- **Archivo:** `REP/controller/ReportesExceptionHandler.java:18`, `EQU/controller/EquiposExceptionHandler.java` (`argumentoInvalido`).
- **Problema:** cualquier `IllegalArgumentException` (incluso una que nazca en Spring, JDK o una librería) se convierte en 400 con `ex.getMessage()` en `detail`, lo que puede filtrar texto interno y esconde errores reales (que deberían ser 500). Ninguno de los dos tiene catch-all `ERROR_INTERNO`, ni maneja JSON ilegible, ni `DataIntegrityViolation`. Además registro usa `type` = `https://biohub/errores/validacion`, y los demás `about:blank` (contrato §2).
- **Corrección:** excepciones propias de validación (como en registro/ubicación) y catch-all con mensaje fijo; unificar `type`.
- **Esfuerzo:** S.

### BE-R-22 (Bajo) Gateway: el circuito trata las respuestas 5xx como éxito
- **Archivo:** `api-gateway/src/main/java/co/edu/unbosque/biohub/gateway/service/ProxyService.java:162`.
- **Problema:** `registrarExito` se llama con cualquier respuesta HTTP; solo `ResourceAccessException` cuenta como fallo. Un servicio que responde 500/503 de forma sostenida no abre el circuito (D52). Además las respuestas se almacenan completas en memoria (`readAllBytes`), correcto para las fotos reducidas (≤ 9 MB) pero no para respuestas grandes.
- **Corrección:** contar 502/503/504 (y quizá 500) como fallo; `StreamingResponseBody` si se esperan descargas grandes.
- **Esfuerzo:** S.

### BE-R-23 (Bajo) Duplicación de detectores y de generadores de PDF/CSV
- **Detectores de coordenadas:** cuatro implementaciones con reglas distintas (`ValidadorTextoPublico`, `eventos.service.DetectorCoordenadas`, `ubicacion.domain.DetectorCoordenadasEnTexto` y la copia de consulta-publica). Es deliberado entre servicios (D4: sin módulo común), pero dentro de `servicio-cientifico` son tres con reglas divergentes, que es la causa de BE-R-1.
- **Reportes:** dos generadores de PDF (`PdfPresion` y `construirPdfSimple` hecho a mano) y dos de CSV (separador `;` con BOM y separador `,`). El PDF simple no pagina: con más de ~54 líneas el texto cae fuera de la página (`MediaBox [0 0 612 842]`, 14 pt por línea), por ejemplo con 33 departamentos más el detalle de 40 filas.
- **Corrección:** un solo detector por servicio (el más estricto) y reutilizar `PdfPresion`/`celda` en las exportaciones inmediatas.
- **Esfuerzo:** M.

### BE-R-24 (Bajo) Decodificar imágenes sin límite de dimensiones
- **Archivo:** `CIE/registro/service/FotoPublica.java` (`ImageIO.read`), `LimpiadorMetadatos` (solo recorre segmentos).
- **Problema:** un PNG de 9 MB puede declarar decenas de miles de píxeles por lado y, al decodificarse para reducir la foto pública, consumir gigas de heap. Lo sube un usuario autenticado, así que el riesgo es interno, pero el decodificado ocurre en un endpoint público (BE-R-14).
- **Corrección:** leer ancho y alto de la cabecera (`ImageReader.getWidth/getHeight`) y rechazar por encima de, por ejemplo, 12000x12000 o 50 megapíxeles, al subir.
- **Esfuerzo:** S.

---

## Bien hecho

- **Contrato de registro.** Rutas, códigos (201 con `Location`, 200 al repetir una evidencia, 400/404/409/413) y `problem+json` con `codigo` y `errores[]` coinciden con 04 §13 en los caminos felices y de validación. Las coordenadas no tocan ninguna tabla de registro, ni el historial, ni el snapshot; `toString()` de los DTO las enmascara.
- **Transacción de alta.** `HallazgoService.registrar` inserta primero, anuncia a ubicación con un evento de dominio síncrono dentro de la misma transacción (`MANDATORY` en ubicación) y traduce los errores de ubicación a 400 por nombre de clase, sin romper D53. El snapshot público es una lista fija de campos, no la entidad serializada.
- **Historial append-only.** El Builder impide versiones incompletas o sin justificación; la BD tiene `CHECK`, `UNIQUE` y los permisos sin `UPDATE/DELETE` (D60), con un test que falla si aparece una tabla nueva sin decidir si es historial. La auditoría campo a campo solo registra diferencias reales.
- **Evidencias.** Formato por contenido (no por extensión), EXIF/XMP/comentarios eliminados de JPEG y PNG por un parser propio con rechazo de archivos mal formados, nombre en disco solo con UUID y comprobación de ruta, escritura atómica, deduplicación por SHA-256, descarga como adjunto con `nosniff` y `no-store`.
- **Consumidores idempotentes en consulta-publica.** `UPDATE ... WHERE version < ?`, fallback a `INSERT` con `DuplicateKeyException`, validación estricta (tipos sin coerción, duplicados de clave, tamaño, origen, `user_id` AMQP, coordenadas descartadas sin DLQ), caché invalidada solo después del commit, alertas por saltos de versión anómalos. Es el mejor módulo de eventos del proyecto.
- **Reportes asíncronos.** 202 + `Location`, estados claros, cola propia con DLQ, publicación AFTER_COMMIT, el consumidor de solicitudes ignora duplicados, el cálculo se hace por agregación SQL sobre la base analítica (nunca la del científico), y los archivos se regeneran desde el resumen guardado. `CalculadorIndicePresion` es una función pura, documentada y con test. El CSV mensual neutraliza fórmulas y está pensado para Excel en español.
- **Equipos.** `CHECK` en BD que liga el estado `ASIGNADO` con custodio y fecha, puerto `ValidadorCustodioPort` con adaptador, validación de que el custodio esté activo y sea `investigador_campo`, paginación acotada.
- **Catálogo y foto (D65).** Consultas parametrizadas con escape de `%`/`_`, tope de tamaño de página, el endpoint de foto pública sin token depende del aislamiento de red y del gateway (que exige rol en `/api/cientifico/**`), la foto se reduce y recodifica sin metadatos, cliente HTTP con timeouts y degradación a 404 si el científico no responde.
- **Seguridad transversal.** `@PreAuthorize` en cada controlador más `denyAll` por defecto en los servicios y tests de arquitectura que lo verifican (`ArquitecturaSeguridadTest`, `TodosLosEndpointsExigenRolTest`); gateway con firewall, validación de rutas, cabeceras `X-Forwarded-*` fijadas por el gateway y límite de cuerpo.

## Calificación: 7,0 / 10

**Justificación.** La arquitectura es sólida y se cumple lo difícil: separación de escritura y lectura, historial inmutable con permisos en BD, cifrado, consumidores idempotentes con validación estricta, reportes asíncronos y sin coordenadas filtradas. Hay mucho trabajo de seguridad y tests (más de 140 clases de test). Se descuenta porque los defectos Altos caen justo en la frontera entre escritura y lectura, que es la promesa del proyecto: un evento puede perderse sin recuperación (BE-R-2) o no salir por un detector que discrepa del validador (BE-R-1), el historial puede fallar al leerse con campos opcionales vacíos (BE-R-3), el departamento no se normaliza antes de agregarse en reportes (BE-R-5) y los atributos flexibles no se validan en el servidor (BE-R-4). Además, el manejo de errores y la concurrencia de registro, equipos y reportes están a medio camino (BE-R-6, 8, 12, 13) y la lógica más delicada (publicación después del commit) no tiene test propio. Con BE-R-1 a BE-R-5 corregidos (esfuerzo total aproximado de 3-4 días) la nota sube cerca de 8,5.

## Orden sugerido de corrección

1. BE-R-1, BE-R-3, BE-R-6 (todos S: un día, eliminan 500 y desfases).
2. BE-R-4, BE-R-5 (M).
3. BE-R-2 + BE-R-7 (outbox, M).
4. BE-R-8, BE-R-9, BE-R-12 (S/M), luego BE-R-17 (tests de lo anterior).
5. Medios y Bajos restantes según tiempo.
