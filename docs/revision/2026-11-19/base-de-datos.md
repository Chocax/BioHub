# Revisión de bases de datos (2026-11-19)

Rama `develop` (HEAD `8d23981`). Revisión de solo lectura. Autor: DBA. No se tocó código, migraciones ni datos; no se arrancó ni paró ningún contenedor; no se usó Aiven.

## 1. Resumen y calificación

**Calificación: 7,5 / 10.**

La separación física escritura/lectura/analítica/gateway es real y está bien aislada (instancias MySQL distintas, usuarios distintos, sin FK ni consultas cruzadas), el modelo de lectura no tiene ninguna columna de coordenadas (verificado en la base viva) y hay defensa en profundidad contra la fuga de coordenadas en tres capas. Lo que resta nota: no hay outbox ni mecanismo de reproyección (un evento perdido deja el modelo de lectura desactualizado para siempre), el historial append-only solo se impone en Aiven y no en el MySQL local, las fotos públicas rompen la regla "consulta pública no toca lo transaccional", y hay datos inconsistentes entre `hallazgo.departamento` (texto libre) y los modelos derivados.

### Limitación de la verificación en vivo

En el momento de la revisión estaban arriba los contenedores MySQL (transaccional, lectura, gateway y, desde mitad de la revisión, analítica), Redis, RabbitMQ y Keycloak, pero **ningún servicio Spring Boot** (puertos 8080 a 8084 sin escucha; qa-tester ejecutaba pruebas con Testcontainers). Por eso:

- Verificado con consultas reales: existencia y separación de las bases, tablas, columnas, usuarios y GRANTs, historial de Flyway, topología de RabbitMQ.
- **No verificado en vivo:** el flujo extremo a extremo registro, evento, proyección, caché (no había servicios que registraran). Se valida por lectura de código y por las pruebas existentes.
- Los volúmenes de MySQL son del 2026-11-08: la base `cientifico` solo tiene 3 de las 6 migraciones del repositorio y `equipos` está vacía (sin tablas). No es un error de código: las migraciones pendientes se aplican al arrancar `servicio-cientifico` y `servicio-equipos` (Flyway). Quien pruebe debe saber que el estado actual no refleja HEAD.

## 2. Qué servicio escribe en qué base (evidencia en `application*.yml`)

| Servicio | Contenedor / puerto (perfil `local`) | Base y usuario | Rol | Evidencia |
|---|---|---|---|---|
| servicio-cientifico (8081) | `biohub-mysql-transaccional` :3307 | `cientifico` / `cientifico_app` | ESCRITURA | `servicio-cientifico/src/main/resources/application-local.yml:5-7` |
| servicio-equipos (8082) | `biohub-mysql-transaccional` :3307 | `equipos` / `equipos_app` | ESCRITURA | `servicio-equipos/.../application-local.yml:5-7` |
| servicio-consulta-publica (8083) | `biohub-mysql-lectura` :3308 (+ Redis caché) | `consulta_publica` / `consulta_app` | LECTURA (modelo de lectura) | `servicio-consulta-publica/.../application-local.yml:5-7` |
| servicio-reportes (8084) | `biohub-mysql-analitica` :3309 | `reportes` / `reportes_app` | ANALÍTICA (proyección propia) | `servicio-reportes/.../application-local.yml:5-7` |
| api-gateway (8080) | `biohub-mysql-gateway` :3310 | `gateway` / `gateway_app` | API keys y consumidores | `api-gateway/.../application-local.yml:5-7` |

Un solo `DataSource` por servicio (`grep DataSource|3307|MYSQL_TRANSACCIONAL` solo aparece en los `application-local.yml` de cientifico y equipos). `ddl-auto: validate` y Flyway en los cinco.

### Evidencia de separación (consultas reales)

- `SHOW DATABASES` en `mysql-transaccional`: solo `cientifico` y `equipos`. En `mysql-lectura`: solo `consulta_publica`. En `mysql-analitica`: solo `reportes`. En `mysql-gateway`: `gateway`. Ninguna base de un servicio existe en la instancia de otro.
- Usuarios: `cientifico_app` y `equipos_app` solo en transaccional; `consulta_app` solo en lectura; `reportes_app` solo en analítica; `gateway_app` solo en gateway. Cada GRANT es sobre su propio esquema (`GRANT ALL PRIVILEGES ON cientifico.* ...`).
- `consulta_publica` tiene 3 tablas (`departamento` 33 filas idénticas a `cientifico.ubicacion_departamento` + `ubicacion_region`, `ficha_publica` 0 filas, `flyway_schema_history`) y **0 columnas** cuyo nombre contenga lat, lon, lng, coord, geom, point, investigador, usuario, autor o cifrad (consulta a `information_schema.columns`). `SHOW CREATE TABLE ficha_publica` confirma las columnas públicas: solo `departamento_codigo/nombre` y `region_nombre`.
- La prueba `MigracionesIntegracionTest.laFichaPublicaNoTieneColumnasDeCoordenadasNiDeUsuario` hace esa misma comprobación en CI.
- La consulta pública NO usa un datasource transaccional: lee solo de `ficha_publica` y `departamento` en `BusquedaFichasJdbc` y `BusquedaEspeciesJdbc` (solo columnas públicas). **Excepción:** las fotos (DB-R-3).
- RabbitMQ (`rabbitmqctl`): exchange `biohub.eventos` con las colas durables `consulta-publica.eventos` y `reportes.eventos` enlazadas a `hallazgo.#` y `prueba.#`, y sus DLQ por `biohub.eventos.dlx`. `reportes.eventos` tenía 9 mensajes retenidos con 0 consumidores (reportes aún sin arrancar): muestra el amortiguamiento y la consistencia eventual del diseño.

## 3. Sincronización del modelo de lectura

Flujo: `HallazgoService.registrar/reclasificar` (transacción en `cientifico`) publica un `CambioHallazgoConfirmado` en memoria; `PublicadorCambiosHallazgo` (`@TransactionalEventListener(AFTER_COMMIT)`) llama a `PublicadorEventos`, que envía el sobre persistente al exchange `biohub.eventos` con publisher confirm y `user_id`. `ConsumidorEventos` valida el sobre y el origen y delega en `ProcesadorHallazgos`, que valida (`ValidadorDatosHallazgo`), proyecta con `ProyectorFichas` en una transacción en `mysql-lectura` y, solo si hubo cambio y ya con commit, invalida Redis (`fichas::<id>` y búsquedas).

Propiedades correctas:
- Eventos tipo snapshot completo y versionado: la proyección es idempotente y tolera desorden (`UPDATE ... WHERE hallazgo_id = ? AND version < ?`, luego `INSERT`; un duplicado se ignora). `EscrituraFichasJdbc.java:20-31`.
- Consistencia eventual aceptada y acotada: la caché tiene TTL (fichas 5 min, búsquedas 60 s) además de la invalidación explícita.
- Reintentos (3) y luego DLQ; reenviar la DLQ es seguro por la idempotencia por versión. Eventos con coordenadas se descartan con ack (no a la DLQ).
- Reportes es idempotente por `evento_procesado_reporte` y además por versión (`HallazgoReporte.actualizarDesdeSnapshot`).

### Coordenadas: solo región/departamento llega a lectura

Defensa en tres capas: (1) el publicador rechaza el evento si `DetectorCoordenadas.contiene(datos)` (`PublicadorEventos.publicar`); (2) el consumidor detecta claves o texto con forma de coordenada, en cualquier profundidad, y descarta con ack; (3) el esquema de `ficha_publica` no tiene columna posible. En `cientifico` las coordenadas existen solo cifradas (AES-GCM) en `ubicacion_hallazgo.coord_cifrada`, con nonce, versión de llave y CHECK de longitudes. Cumple D14/D15.

## 4. Hallazgos

### DB-R-1 (Alto) No hay outbox ni reproyección: un evento perdido deja la lectura desactualizada para siempre

- Archivos: `servicio-cientifico/.../registro/infrastructure/PublicadorCambiosHallazgo.java:18-23,37-44`; `servicio-cientifico/.../config/RabbitConfig.java:24` (solo declara el exchange); ausencia de cualquier endpoint o tarea de resincronización (búsqueda de `outbox|republic|reproy|reindex|backfill` en `src/main` sin resultados).
- Problema: el evento se publica `AFTER_COMMIT`. Si el broker falla o el proceso muere entre el commit y el envío, solo queda un `log.error` ("hay que republicarlo"). Tampoco existe forma de reconstruir `ficha_publica` ni `hallazgo_reporte` desde `cientifico`. Además, `cientifico` no declara las colas: si un consumidor aún no declaró su cola cuando se publica, el broker descarta el mensaje (exchange topic sin binding, sin `mandatory`), y una base de lectura nueva (por ejemplo la de Aiven) nace vacía sin forma de poblarla con el historial.
- Por qué importa: la promesa de CQRS es que la lectura converge. Aquí la divergencia es permanente e invisible (no hay métrica ni reconciliación). Afecta RF de consulta pública y reportes. El propio R2 de `03-arquitectura.md:543` y `04-contrato-api.md:861` lo reconocen como pendiente.
- Corrección: tabla `evento_pendiente` (outbox) en `cientifico` escrita en la misma transacción que el hallazgo, con un publicador periódico que marque enviados; y un endpoint administrativo de republicación por rango o por `hallazgo_id` que lea `hallazgo` y emita snapshots (son idempotentes por versión). Alternativa mínima para el prototipo: solo el endpoint de republicación desde `hallazgo` (+ `hallazgo_version.snapshot`), más declarar las colas en el publicador.
- Esfuerzo: M (outbox + republicador), S (solo el endpoint).

### DB-R-2 (Alto) El historial append-only no se impone en el MySQL local

- Archivos: `infra/mysql/transaccional/01-esquemas-y-usuarios.sh:25-26` (`GRANT ALL PRIVILEGES ON cientifico.*`); verificado con `SHOW GRANTS FOR 'cientifico_app'@'%'` en el contenedor vivo: `GRANT ALL PRIVILEGES ON cientifico.*`. En Aiven sí existe la restricción: `deploy/aiven/03-registro-historial-append-only.sql.example`.
- Problema: en el entorno que se usa para demostrar (Docker local), el usuario de la aplicación puede `UPDATE`, `DELETE`, `TRUNCATE`, `DROP` y `ALTER` sobre `hallazgo_version` y `hallazgo_auditoria`. La inmutabilidad (RNF10, HU-05 criterio 2, ASR7: "bloquear físicamente") solo se sostiene en el código y en los CHECK. D60 lo asume ("en local no aplica"), pero el criterio de aceptación es verificable solo en Aiven.
- Por qué importa: un evaluador que ejecute `UPDATE hallazgo_version ...` con las credenciales de la app en local lo logra; y la aplicación con DDL contradice el mínimo privilegio que se declara en D37.
- Corrección: replicar el modelo de Aiven en local: usuario `cientifico_migr` (DDL, para Flyway) y `cientifico_app` solo DML con `REVOKE UPDATE, DELETE` en las dos tablas, aplicado por un script que se ejecute después de Flyway (o un `init` posterior al arranque del servicio). Mismo patrón para `equipos`, `consulta_publica`, `reportes` y `gateway` (hoy todos `ALL PRIVILEGES`; en lectura/analítica/gateway las contraseñas de aplicación nunca deberían poder hacer DDL).
- Esfuerzo: M.

### DB-R-3 (Medio) La consulta pública depende en vivo del servicio científico (y de la base transaccional) para las fotos

- Archivos: `servicio-consulta-publica/.../infrastructure/FotosCientificoHttp.java:27-43`; `servicio-consulta-publica/.../service/FotosEspeciesService.java:19-60`; consulta de origen `EvidenciaHallazgoRepository.fotosPublicasDeEspecie`; contradice la regla 2 de `docs/equipo/03-arquitectura.md:205` ("consulta pública no llama a servicio-cientifico: solo eventos").
- Problema: cada foto no cacheada sale de `mysql-transaccional` (tabla `hallazgo_evidencia` y el volumen de archivos) a través de HTTP. La caché es en memoria por instancia, con un tope de 120 entradas y vigencia 10 min (2 min para "sin foto"). Un catálogo con más de 120 especies, o un cliente que pida nombres distintos, hace fallar la caché y empuja carga pública a la base de escritura, que es lo que ASR2/ASR4 prohíben. El endpoint de científico es `permitAll()` (solo protegido por la red interna).
- Por qué importa: rompe la independencia de la lectura y el aislamiento de picos; además el mapa de qué especies tienen foto no está en el modelo de lectura.
- Corrección: llevar la foto al modelo de lectura (columna `foto_ruta`/`tiene_foto` o tabla `especie_foto` alimentada por un evento `evidencia.publica`) y servirla desde un almacén propio de lectura, o como mínimo subir el tope de caché y limitar el rate en el gateway para este endpoint.
- Esfuerzo: M.

### DB-R-4 (Medio) `hallazgo.departamento` es texto libre y los modelos derivados quedan inconsistentes; `region` en reportes queda siempre NULL

- Archivos: `HallazgoService.java:91` (`comando.departamento().strip()`); `V20261104_1000__registro_hallazgo.sql:44` (`departamento VARCHAR(60)` sin FK al catálogo); `ConstructorSnapshotPublico.java:50` (`datos.put("departamento", hallazgo.getDepartamento())`); `ProyectorHallazgosService.java:48` (lee `datos.get("region")`, que el publicador nunca envía; el snapshot no tiene `region`).
- Problema: el valor que se guarda y se publica es el que escribió el usuario (puede ser el código DANE "05" o el nombre, con o sin tildes). `ubicacion` lo valida contra el catálogo y guarda el código canónico en `ubicacion_hallazgo.departamento_codigo`, pero `hallazgo.departamento` y el evento llevan el texto crudo. `consulta_publica` lo normaliza con su catálogo; `reportes` guarda el texto tal cual, por lo que `GROUP BY departamento` (RF16, presión por departamento) puede dividir un mismo departamento en "05" y "Antioquia". La columna `hallazgo_reporte.region` queda siempre NULL y se exporta vacía en el CSV (`ConsultasReportesService.java:72-83`).
- Por qué importa: reportes de presión por departamento con conteos partidos; dato duplicado y sin integridad referencial.
- Corrección: guardar en `hallazgo` el nombre oficial resuelto (o `departamento_codigo` con FK a un catálogo local), publicar en el snapshot `departamentoCodigo`, `departamento` (nombre oficial) y `region`, y en reportes agrupar por código. Añadir la migración correspondiente (no editar migraciones aplicadas).
- Esfuerzo: M.

### DB-R-5 (Medio) `ubicacion_hallazgo` sin FK a `hallazgo` aunque ya están en el mismo esquema

- Archivos: `V20261026_1010__ubicacion_hallazgo.sql:2`; `docs/equipo/05-modelo-datos.md:35,375-386` ("FK futura").
- Problema: la FK prometida nunca se creó (el DDL de `hallazgo` existe desde `V20261104_1000`). Puede haber filas de ubicación huérfanas o hallazgos sin ubicación; la integridad depende solo de la transacción de la aplicación. Además `ubicacion_hallazgo` duplica `nombre_cientifico`, `fecha_recoleccion`, `investigador`, `estado_conservacion` y `version` (índice de búsqueda interno); la duplicación está justificada, pero sin FK nada detecta el desvío.
- Corrección: migración `ALTER TABLE ubicacion_hallazgo ADD CONSTRAINT fk_ubicacion_hallazgo_hallazgo FOREIGN KEY (hallazgo_id) REFERENCES hallazgo(id) ON DELETE RESTRICT`, previa comprobación de que no hay huérfanos. Actualizar `deploy/aiven/03` si aplica (no afecta permisos).
- Esfuerzo: S.

### DB-R-6 (Medio) `equipo_trabajo` sin control de concurrencia ni historial de custodia

- Archivos: `V20261115_1800__crear_tablas_equipos.sql:1-27`; `EquipoTrabajoService.java:43-59` (`asignar`: lee, comprueba estado, guarda); la entidad no tiene `@Version` (búsqueda de `@Version|@Lock` sin resultados en equipos).
- Problema: dos asignaciones concurrentes del mismo equipo a custodios distintos pasan ambas la comprobación `DISPONIBLE` y gana la última escritura. El CHECK de coherencia estado/custodio no lo evita porque ambas filas finales son válidas. Cada reasignación sobrescribe el custodio anterior sin rastro, aunque el SRS pide "trazabilidad" y control riguroso de activos del Estado (HU de equipos).
- Corrección: columna `version INT NOT NULL DEFAULT 0` con `@Version` (o `UPDATE ... WHERE estado='DISPONIBLE'` y comprobar filas afectadas) y, si se quiere trazabilidad, tabla `equipo_asignacion` append-only (equipo, custodio, desde, hasta, asignado_por).
- Esfuerzo: S (versión), M (historial).

### DB-R-7 (Medio) Reclasificación concurrente: protegida por UNIQUE pero termina en 500, y `hallazgo` sin `@Version`

- Archivos: `HallazgoService.java:126-` (`reclasificar`); `Hallazgo.java:28` ("No es un @Version de JPA"); `V20261104_1000__registro_hallazgo.sql` (`uq_hallazgo_version UNIQUE (hallazgo_id, version)`); `RegistroExceptionHandler` no mapea `DataIntegrityViolationException`.
- Problema: dos reclasificaciones simultáneas leen la versión n y escriben n+1; la restricción UNIQUE de `hallazgo_version` evita la corrupción del historial (bien), pero el segundo usuario recibe un 500 en lugar de un 409 con mensaje claro.
- Corrección: `@Version` o `SELECT ... FOR UPDATE` sobre `hallazgo`, y traducir la violación de unicidad a 409.
- Esfuerzo: S.

### DB-R-8 (Medio) Consulta "más recientes por especie" carga todas las filas de cada especie

- Archivo: `BusquedaEspeciesJdbc.java:85-89`.
- Problema: `WHERE nombre_cientifico IN (...) ORDER BY fecha_recoleccion DESC, actualizado_en DESC` devuelve **todos** los registros de las especies de la página y se queda con el primero de cada una en memoria (`putIfAbsent`). Con carga alta (muchos hallazgos por especie) y sin un índice `(nombre_cientifico, fecha_recoleccion)`, hay filesort sobre todos los registros. Los índices existentes (`idx_ficha_publica_nombre`, `familia_nombre`, `depto_nombre`) no cubren este orden.
- Corrección: resolver con una sola consulta con función de ventana (`ROW_NUMBER() OVER (PARTITION BY nombre_cientifico ORDER BY fecha_recoleccion DESC, actualizado_en DESC)` y `rn = 1`) o un `JOIN` contra un subselect `MAX(fecha)`, y un índice `(nombre_cientifico, fecha_recoleccion DESC)` en lugar de `idx_ficha_publica_nombre` (el nuevo cubre sus usos). Ver también nota en el punto "Índices".
- Esfuerzo: S.

### DB-R-9 (Bajo) Reportes: filtros con `LOWER()/UPPER()` anulan los índices y son redundantes

- Archivo: `servicio-reportes/.../domain/HallazgoReporteRepository.java:14-16,35-36`.
- Problema: la collation `utf8mb4_0900_ai_ci` ya es insensible a mayúsculas y tildes, así que `LOWER(h.departamento) = LOWER(:p)` no aporta nada y deshabilita `idx_hallazgo_reporte_departamento` y `idx_hallazgo_reporte_familia`; el patrón `(:p IS NULL OR ...)` además produce un solo plan para todas las combinaciones. Los índices de `hallazgo_reporte` hoy solo ayudan al filtro por fecha.
- Corrección: quitar `LOWER/UPPER` (normalizar `estado_conservacion` al escribir, que ya se hace con `toUpperCase`), y construir el WHERE solo con los filtros presentes.
- Esfuerzo: S.

### DB-R-10 (Bajo) Revisión de normalización: dependencia transitiva especie a familia en `hallazgo`

- Archivo: `V20261104_1000__registro_hallazgo.sql:38-52`.
- Problema: `familia`, `nombre_comun` y `nombre_cientifico` se repiten en cada hallazgo (`nombre_cientifico` determina `familia`); no hay tabla de taxón, así que dos hallazgos de la misma especie pueden tener familias distintas y `ficha_plantilla` se liga por texto (`nombre_cientifico`), sin FK. Para el prototipo es una desnormalización razonable (la reclasificación cambia nombre y familia por versión), pero no está justificada en `05-modelo-datos.md`.
- Corrección: documentarlo como decisión (3FN con excepción justificada) o añadir tabla `taxon` en una iteración posterior.
- Esfuerzo: S (documentar) / L (modelar).

### DB-R-11 (Bajo) Índices redundantes

- `ix_hallazgo_evidencia_hallazgo (hallazgo_id)` es prefijo de `uq_hallazgo_evidencia_sha (hallazgo_id, sha256)`; `ix_hallazgo_version_hallazgo (hallazgo_id, version DESC)` repite `uq_hallazgo_version (hallazgo_id, version)` (InnoDB recorre el índice en orden inverso). Costo de escritura sin beneficio. Eliminar con una migración nueva (`DROP INDEX`; el usuario de migración tiene DROP en Aiven). Esfuerzo: S.

### DB-R-12 (Bajo) Documentación del modelo desactualizada

- `docs/equipo/05-modelo-datos.md` no describe: las tablas de registro (`hallazgo`, `hallazgo_version`, `hallazgo_auditoria`, `hallazgo_evidencia`, `ficha_plantilla`), `equipo_trabajo`, `hallazgo_reporte`/`evento_procesado_reporte`, ni la migración `V20261119_1000__evidencia_publica.sql` (D65). Su §1 sigue diciendo "no se diseña ninguna tabla de Hernando".
- `V20261104_1000__registro_hallazgo.sql:75` dice "y con los triggers de mas abajo" pero no hay triggers (D60 los descartó).
- `03-arquitectura.md` §J5 describe `SELECT ... FOR UPDATE` en la proyección; el código usa `UPDATE ... WHERE version < ?` atómico (mejor, pero hay que actualizar el texto). `03-arquitectura.md:205` ignora la llamada de fotos (DB-R-3).
- `05-modelo-datos.md` no incluye el diagrama ER de las 5 bases.
- Corrección: actualizar el documento y el comentario (los comentarios de una migración aplicada se pueden dejar y aclarar en el documento: Flyway valida checksum). Esfuerzo: S.

### DB-R-13 (Bajo) Verificaciones de coordenadas más débiles en reportes

- `ProyectorHallazgosService.contieneCoordenadas` (`:76-80`) solo mira 3 claves y únicamente para `EN_PELIGRO`. No hay riesgo de almacenamiento (la tabla no tiene columnas de coordenadas y solo se leen campos concretos), pero es inconsistente con el detector completo de consulta pública. Reutilizar el mismo detector. Esfuerzo: S.

### DB-R-14 (Bajo) Texto libre `descripcion` publicado tal cual

- `ficha_publica.descripcion TEXT` recibe el texto libre del investigador (hasta 5000 caracteres). El detector rechaza coordenadas, pero no descripciones de lugar ("vereda X, finca Y, junto al puente"), que pueden revelar la ubicación exacta de especies en peligro. Valorar publicar la descripción solo cuando el estado no es `EN_PELIGRO`, o una descripción pública separada que elija el investigador (como ya se hizo con las fotos, D65). Esfuerzo: M.

### DB-R-15 (Bajo) Datos de demostración y semillas

- Solo hay semillas de catálogo (regiones y departamentos) en las migraciones, correcto. D-docs hablan de un perfil `local-demo` (`03-arquitectura.md:526`, `05 §13`) que no existe en el código; hay `scripts/sembrar-demo.mjs` (vía API) y `pruebas-carga/datos/semilla-fichas.sql` (solo lectura, para carga). No existe semilla coherente para equipos ni reportes. Alinear la documentación o crear la semilla. Esfuerzo: S.

### DB-R-16 (Bajo) Migraciones con más de una sentencia DDL

- `V20261119_1000__evidencia_publica.sql` hace `ALTER TABLE` y `CREATE INDEX` en el mismo archivo. En MySQL el DDL no es transaccional: si falla el segundo, el primero queda aplicado y Flyway marca la migración como fallida (obliga a `repair`). Preferible una sentencia DDL por migración. Esfuerzo: S.

### DB-R-17 (Bajo) Crecimiento sin purga

- `evento_procesado_reporte` crece una fila por evento sin purga; los binlogs están activos (`log_bin=1`, ROW) en los contenedores locales. Añadir una tarea de purga (por ejemplo, 90 días) o quitar la tabla y apoyarse solo en la versión (que ya hace la proyección idempotente). Esfuerzo: S.

## 5. Migraciones Flyway: orden y consistencia

| Servicio | Migraciones (orden) | Observaciones |
|---|---|---|
| cientifico | `V1` (vacía), `20261026.1000` catálogo, `20261026.1010` ubicación, `20261104.1000` registro, `20261116.1000` `subido_por`, `20261119.1000` `publica` | Orden numérico correcto con `out-of-order: true`. Volumen local en la 3.ª (pendientes 3). |
| equipos | `V1`, `20261115.1800` | Volumen local: sin migraciones aplicadas (sin tablas). |
| consulta-publica | `V1`, `20261026.1000`, `20261026.1010` | Aplicadas las 3 en el volumen vivo. |
| reportes | `V1`, `20261115.1815`, `20261116.1100` | Analítica sin tablas hasta que arranque el servicio. |
| gateway | `V1`, `20261105.1200` | Aplicadas. |

- `ddl-auto: validate` en los cinco servicios; `flyway.user` separado del usuario de ejecución con `*_MIGR_USER` (SEC-11). Las migraciones respetan las reglas de Aiven (sin triggers, PK en todas las tablas, `deploy/aiven/verificar-migraciones.sh` las comprueba) y hay pruebas Testcontainers (`MigracionesUbicacionTest`, `MigracionesRegistroTest`, `MigracionesIntegracionTest`).
- No pude ejecutar las migraciones contra una base limpia sin arrancar contenedores; la validación se hizo por lectura y se apoya en esas pruebas.

## 6. Fichas flexibles (atributos variables)

Bien resuelto para ASR5: `ficha_plantilla.atributos JSON` (array de definiciones: clave, etiqueta, tipo, obligatorio, habilitado, unidad) con versión por especie (`UNIQUE (nombre_cientifico, version)`, solo se agregan versiones) y `hallazgo.atributos JSON` (objeto) + `plantilla_version` para saber con qué plantilla se capturó. Agregar un campo no altera el esquema ni los hallazgos antiguos. Los CHECK garantizan `JSON_TYPE` array/object; la validación de valores contra la plantilla vive en la aplicación (esperable). Limitaciones asumidas: no se puede consultar o filtrar por atributo con índices (no hay columnas generadas), y `plantilla_version` no tiene FK (referencia lógica por texto).

## 7. Índices frente a consultas reales

| Consulta (origen) | Índice | Veredicto |
|---|---|---|
| Búsqueda pública por `q` (FULLTEXT, `BusquedaFichasJdbc`) | `ft_ficha_publica_nombres`; `LIKE 'xx%'` con `idx_ficha_publica_nombre` (token mínimo InnoDB = 3) | Correcto, con fallback por prefijo para 1 a 2 letras |
| Filtro por familia / departamento + orden por nombre | `idx_ficha_publica_familia_nombre`, `idx_ficha_publica_depto_nombre` | Correcto |
| Ficha por id | PK `hallazgo_id` | Correcto |
| Especies agrupadas (`GROUP BY nombre_cientifico`) | `idx_ficha_publica_nombre` | Aceptable |
| Más recientes por especie | ninguno adecuado | Mejorar (DB-R-8) |
| Hallazgos por investigador / especie / estado (cientifico) | `ix_hallazgo_investigador_fecha`, `ix_hallazgo_especie_fecha`, `ix_hallazgo_estado_fecha` | Correcto |
| Historial y auditoría por hallazgo | `uq_hallazgo_version`, `ix_hallazgo_auditoria_hallazgo` | Correcto (uno redundante, DB-R-11) |
| Búsqueda interna de ubicación (HU-13) | `idx_ubicacion_hallazgo_*` con fecha como 2.ª columna | Bien pensado para el orden por fecha |
| Fotos públicas por especie | `ix_hallazgo_especie_fecha` + `ix_hallazgo_evidencia_publica` | Aceptable |
| Equipos por estado / custodio | `idx_equipo_trabajo_estado`, `idx_equipo_trabajo_custodio` | Correcto |
| Reportes por departamento / familia / estado | índices existen pero `LOWER()/UPPER()` los anula | DB-R-9 |
| API key por prefijo | `uq_api_key_prefijo` | Correcto |

Los `EXPLAIN` no son concluyentes: las tablas están vacías (0 filas), así que el optimizador no discrimina índices. `group_concat_max_len` = 1024 es suficiente para 33 departamentos.

## 8. Integridad referencial y datos sensibles

- FK con `ON DELETE/UPDATE RESTRICT` en todo el modelo (catálogos, historial, evidencias, API keys, archivos de reporte). Un hallazgo no se puede borrar físicamente porque siempre tiene al menos una fila en `hallazgo_version`. Faltan: `ubicacion_hallazgo` a `hallazgo` (DB-R-5) y los vínculos lógicos por texto (`departamento`, `plantilla`).
- Datos personales: `investigador`, `autor`, `subido_por`, `custodio_usuario`, `solicitado_por` (nombres de usuario de Keycloak, atribución obligatoria) y `consumidor_externo.contacto_email`. No se replican a `consulta_publica` ni al snapshot del historial (verificado en el esquema y en `ConstructorSnapshotPublico`). Las API keys se guardan como SHA-256 de 32 bytes con `prefijo` visible, nunca en claro. Las coordenadas van cifradas con AES-256-GCM (llave versionada fuera de la BD). No hay contraseñas en ninguna tabla.

## 9. Bien hecho

- Separación física real en cuatro instancias y esquemas, un usuario por servicio, con aislamiento verificado en vivo; en Aiven, usuarios de migración y de aplicación separados, `REQUIRE SSL` y `MAX_USER_CONNECTIONS`.
- El modelo de lectura no tiene columnas de coordenadas ni de usuario (prueba automática + verificación real) y existen tres barreras contra la fuga de coordenadas.
- Proyección idempotente, atómica y tolerante al desorden por versión; DLQ y descarte con ack para eventos con coordenadas; caché con TTL, invalidación tras commit y degradación si Redis cae.
- CHECK abundantes y bien pensados (estado, versión, justificación obligatoria desde la versión 2, coherencia de las columnas de cifrado, coherencia estado/custodio, coherencia estado/revocación de API key), UNIQUE (clave, nonce) contra reutilización de nonce GCM, todo con `ENGINE=InnoDB` y `utf8mb4`.
- Historial en dos niveles (`hallazgo_version` con snapshot JSON + auditoría campo a campo) y estrategia D60 de permisos por tabla para Aiven, con una prueba (`HistorialAppendOnlyScriptTest`) que rompe el build si se añade una tabla sin línea en el script.
- Flyway disciplinado: versiones por fecha, `validate`, reglas de compatibilidad con Aiven comprobadas por script.
- Catálogo de departamentos idéntico en `cientifico` y `consulta_publica` (33 filas, verificado con diff real).

## 10. Resumen de severidades

| Severidad | IDs |
|---|---|
| Crítico | ninguno |
| Alto | DB-R-1 (sin outbox ni reproyección), DB-R-2 (append-only y mínimo privilegio no aplicados en local) |
| Medio | DB-R-3, DB-R-4, DB-R-5, DB-R-6, DB-R-7, DB-R-8 |
| Bajo | DB-R-9 a DB-R-17 |
