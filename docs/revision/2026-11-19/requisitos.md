# Revisión de requisitos: RF1-RF19 y HU-01..HU-13 (2026-11-19)

Rama `develop`, HEAD `8d23981`. Revisión de solo lectura: no se ejecutó nada; el estado sale de leer el código y las pruebas. Fuente de verdad: `docs/entregas/SRS-BioHub.txt`. Comparación contra `docs/revision/requisitos.md` (2026-11-08, HEAD `6a5495a`).

Regla: **Cumple** = código (endpoint y, si la HU lo implica, pantalla) + prueba automatizada del criterio. **Parcial** = falta prueba, pantalla o algún criterio. **No cumple** = sin código.
Convención de lectura: una pantalla sin prueba propia no baja un criterio a Parcial si la lógica del criterio vive en el backend y está probada; sí lo baja cuando el criterio es la pantalla misma (alerta, comparador). Las pantallas sin prueba se recogen aparte en REQ-R-06.

Abreviaturas de ruta: `CI/` = `servicio-cientifico/src/main/java/co/edu/unbosque/biohub/cientifico/`; `CIT/` = `servicio-cientifico/src/test/java/co/edu/unbosque/biohub/cientifico/`; `GW/` = `api-gateway/src/main/java/co/edu/unbosque/biohub/gateway/`; `GWT/` = `api-gateway/src/test/java/co/edu/unbosque/biohub/gateway/`; `EQ/`, `EQT/` = `servicio-equipos/src/{main,test}/java/co/edu/unbosque/biohub/equipos/`; `RP/`, `RPT/` = `servicio-reportes/src/{main,test}/java/co/edu/unbosque/biohub/reportes/`; `WI/` = `web-interna/src/`; `WP/` = `web-publica/src/`.

## 1. Resumen

| | 08-11 | 19-11 |
|---|---|---|
| RF Cumple / Parcial / No cumple | 7 / 1 / 11 | **16 / 3 / 0** |
| HU Cumple / Parcial / No cumple | 1 / 6 / 6 | **8 / 5 / 0** |
| Criterios de aceptación con código y prueba | 18 de 38 | **32 de 38** (6 Parcial, 0 No cumple) |

- Desde el 08-11 se construyeron `registro`, historial, `servicio-equipos`, `servicio-reportes` y todas las pantallas de `web-interna`. La causa raíz del informe anterior (módulos que eran esqueletos) ya no existe.
- Lo que falta ya no es "sin código" sino **endurecimiento**: validación de atributos en el servidor (HU-02 c.2), inmutabilidad real en la base (HU-05 c.2), pruebas de pantalla, y dos mediciones de rendimiento (HU-06 c.3, HU-09 c.2).
- **Calificación de cumplimiento funcional: 8,0 / 10.** Ponderación: RF 92 % (16 Cumple + 3 Parcial a medias de 19), criterios 92 % ((32 + 3) / 38), HU por puntos Fibonacci 76 % (40 puntos completos + 36 parciales a medias, de 76). Se toma el valor intermedio por la brecha de HU-02 y HU-05, que son las más grandes entre las parciales de menor riesgo técnico.

## 2. Requisitos funcionales

| ID | Estado | Delta vs 08-11 | Endpoint / código | Pantalla | Prueba | Qué falta |
|---|---|---|---|---|---|---|
| RF1 | **Parcial** | No cumple → Parcial | `CI/registro/controller/HallazgoController.java:47-60` (POST), `EvidenciaController.java:47-48` (multipart), `TipoArchivoEvidencia.java:19-24` | `WI/paginas/NuevoHallazgo.jsx:118,126` | `HallazgoServiceTest.java:71`, `EvidenciasMySqlTest.java:77,122`, `RegistroConUbicacionTest.java:42` | "Archivos de datos asociados" no se aceptan: solo JPEG, PNG, WAV, MP3, OGG, FLAC (REQ-R-03). El audio solo se prueba como detección de firma (`LimpiadorMetadatosTest.java:138`), no subiendo un audio por HTTP. |
| RF2 | Cumple | No cumple → Cumple | `CI/registro/controller/FichaPlantillaController.java:36-44,50-54` | `WI/paginas/Plantillas.jsx:108,140` | `FichaPlantillaServiceTest.java:44,55,81`; HTTP en `EspeciesYFotoPublicaMySqlTest.java:102` | La plantilla existe pero el registro no la exige (ver HU-02 c.2). |
| RF3 | Cumple | No cumple → Cumple | Mismo controlador; cada cambio crea versión nueva (`FichaPlantillaService.java:53-71`) | `Plantillas.jsx:140` | `FichaPlantillaServiceTest.java:55,70` (versión nueva, deshabilitar sin borrar) | Nada. |
| RF4 | Cumple | No cumple → Cumple | `HallazgoController.java:65-76` (PUT, justificación obligatoria `HallazgoService.java:132`) | `WI/paginas/DetalleHallazgo.jsx:141` | `HallazgoServiceTest.java:173,186`, `RegistroConUbicacionTest.java:88`, `VersionHallazgoBuilderTest.java:33` | Nada. |
| RF5 | Cumple | No cumple → Cumple | `EQ/controller/EquipoTrabajoController.java:39-50` | `WI/paginas/Equipos.jsx:28` (categorías `:6`) | `EquipoTrabajoServiceTest.java:39,51`, `EquipoTrabajoControllerTest.java:57` | La categoría es texto libre en el backend (`:89-92`); solo la pantalla limita a la lista. |
| RF6 | **Parcial** | No cumple → Parcial | `EquipoTrabajoController.java:73-79`, `EquipoTrabajoService.java:44-60`, `GatewayCustodioAdapter.java:25-45` | `Equipos.jsx:65` | `EquipoTrabajoServiceTest.java:59,75,103` | El alta no exige custodio: un equipo nace `DISPONIBLE` sin investigador (`EquipoTrabajoService.java:27-41`, migración `V20261115_1800__crear_tablas_equipos.sql:20`). El SRS dice "asociar obligatoriamente cada equipo registrado" (REQ-R-04). |
| RF7 | **Parcial** | No cumple → Parcial | `HistorialController.java:33-58`; tablas `hallazgo_version` y `hallazgo_auditoria` (`V20261104_1000__registro_hallazgo.sql:83-114`) | `WI/paginas/Historial.jsx:22-23` | `MigracionesRegistroTest.java:85` (con usuario de permisos reducidos), `HistorialAppendOnlyScriptTest.java:34` | El bloqueo físico depende de un script manual (`deploy/aiven/03-registro-historial-append-only.sql.example:31-37`); el script de entorno da `GRANT ALL` (`infra/mysql/transaccional/01-esquemas-y-usuarios.sh:25`). Ninguna prueba corre la app con ese usuario. Entidades sin `@Immutable` (REQ-R-02). |
| RF8 | Cumple | No cumple → Cumple | `HistorialController.java:33-37,52-57` | `Historial.jsx:34` (comparador) | `HistorialServiceTest.java:47,65,79,87` | Solo se compara por número de versión, no "estado a una fecha" (REQ-R-11, Bajo). |
| RF9 | Cumple | No cumple → Cumple | `HistorialController.java:40-44`, `CambioAuditoria.java` (autor, justificación) | `Historial.jsx:23` | `RegistroConUbicacionTest.java:111`, `HistorialServiceTest.java:47` | Se guarda quien ejecutó, no hay flujo de "autorización" (REQ-R-12, pregunta). |
| RF10 | Cumple | Cumple (igual) | `consulta-publica/.../controller/FichasPublicasController.java:43-57` | `WP/paginas/Inicio.jsx`, `Ficha.jsx` | `BusquedaFichasIntegracionTest`, `FichasPublicasControllerTest`, `WP/pruebas/Inicio.test.jsx`, `web-publica/e2e/portal.spec.js` | Nada. |
| RF11 | Cumple | Cumple (igual) | `ValidadorDatosHallazgo`, `FichaPublica` sin coordenadas | `WP/paginas/Ficha.jsx:126`, `AvisoUbicacionProtegida.jsx` | `SinCoordenadasIntegracionTest`, `Ficha.test.jsx:48` | Nada. |
| RF12 | Cumple | **Parcial → Cumple** | `CI/ubicacion/controller/MapaUbicacionController.java:26-27` | Ruta montada: `WI/App.jsx:104`; menú solo para alto rango `WI/auth/permisos.js:15,42` | `MapaApiTest`, `PoliticaAccesoUbicacionTest`, `WI/ubicacion/pruebas/PaginaMapa.test.jsx`, `WI/App.test.jsx:81`, `WI/ubicacion/e2e/mapa.e2e.js` | Nada. |
| RF13 | Cumple | Cumple (igual) | `CI/ubicacion/service/PoliticaAccesoUbicacion.java`, `denyAll` en los 5 servicios (`SecurityConfig.java`) | n/a | `PoliticaAccesoUbicacionTest`, `SeguridadEndpointsTest`, `MatrizAccesoGatewayTest`, `ArquitecturaSeguridadTest` | Nada. |
| RF14 | Cumple | Cumple (igual) | `GW/controller/ApiExternaController.java:24` | `WI/paginas/ApiKeys.jsx` (administración) | `GWT/GatewayIntegracionTest.java:186,245`, `ApiKeyRateLimitFilterTest.java:103` | Nada. |
| RF15 | Cumple | No cumple → Cumple | `RP/controller/SolicitudesReporteController.java:54-70` (202 asíncrono), `RP/infrastructure/ColaSolicitudesReporte.java:58,70` (cola y tarea mensual) | `WI/paginas/Reportes.jsx:92` | `SolicitudesReporteIntegracionTest.java:125,205`, `CalculadorIndicePresionTest.java:19` | Nada. |
| RF16 | Cumple | No cumple → Cumple | `RP/service/CalculadorIndicePresion.java:42-69` (cruce mes contra 3 meses previos, alerta) | `Reportes.jsx` | `SolicitudesReporteIntegracionTest.java:137-142`, `CalculadorIndicePresionTest.java:19-62` | Es un sustituto: mide especies en peligro, no deforestación (`CalculadorIndicePresion.java:14-17`). Aceptable por el "menor escala" del SRS, pero hay que declararlo (REQ-R-10). |
| RF17 | Cumple | Cumple (ahora con pantalla) | `GW/controller/UsuariosController.java:58` | `WI/paginas/Usuarios.jsx:31` | `KeycloakAdminAdapterIntegracionTest.java:124`, `UsuariosControllerTest`, `ServicioUsuariosTest` | Nada. |
| RF18 | Cumple | Cumple (igual) | JWT en gateway y servicios; PKCE `WI/auth/keycloak.js` | Login por Keycloak | `GatewaySecurityTest`, `MatrizAccesoGatewayTest`, `WI/App.test.jsx:57` | Nada. |
| RF19 | Cumple | Cumple (ahora con pantalla) | `UsuariosController.java:66,73` | `Usuarios.jsx:106-107` | `GatewayIntegracionTest.java:362` | Nada. |

Conteo RF: **Cumple 16** (RF2, 3, 4, 5, 8, 9, 10-19), **Parcial 3** (RF1, RF6, RF7), **No cumple 0**.

## 3. Historias de usuario, por criterio

| HU | Criterio | Estado | Delta | Evidencia (endpoint · pantalla · prueba) | Qué falta |
|---|---|---|---|---|---|
| HU-01 | c.1 campos obligatorios (nombre, fecha, investigador, tipo) | Cumple | No cumple → Cumple | `HallazgoService.java:79-84` (investigador sale del JWT, `HallazgoController.java:90-93`) · `NuevoHallazgo.jsx:85-89` · `HallazgoServiceTest.java:136` | Nada. |
| HU-01 | c.2 adjuntos (fotos, audio) | Cumple | No cumple → Cumple | `EvidenciaController.java:47-48` · `NuevoHallazgo.jsx:126` · `EvidenciasMySqlTest.java:77,122,130` | Ver RF1: archivos de datos no admitidos. |
| HU-01 | c.3 alerta "Campos obligatorios incompletos" | **Parcial** | No cumple → Parcial | Texto exacto solo en `NuevoHallazgo.jsx:105`; el backend responde 400 por campo (`RegistroExceptionHandler.java:39-43`) | Ninguna prueba de pantalla verifica el mensaje (REQ-R-05). |
| HU-02 | c.1 agregar, renombrar, deshabilitar atributos sin código | Cumple | No cumple → Cumple | `FichaPlantillaController.java:36-44` · `Plantillas.jsx:140` · `FichaPlantillaServiceTest.java:55,70,81` | Nada. |
| HU-02 | c.2 el formulario renderiza y valida los campos nuevos | **Parcial** | No cumple → Parcial | Render y validación en el navegador (`WI/componentes/CamposAtributos.jsx`, `NuevoHallazgo.jsx:94`). En el servidor, `FichaPlantillaService.validarValores` (`:95`) **no tiene ningún llamador** y `HallazgoService.java:92` guarda `plantillaVersion = null` | El API acepta cualquier mapa de atributos (REQ-R-01). Tampoco hay prueba de pantalla. |
| HU-02 | c.3 no altera registros históricos | Cumple | No cumple → Cumple | Plantilla versionada en su propia tabla (`FichaPlantillaService.java:68-71`) · `FichaPlantillaServiceTest.java:55` | No se registra con qué versión de plantilla se guardó cada hallazgo (parte de REQ-R-01). |
| HU-03 | c.1 editar campos clave con rol científico | Cumple | No cumple → Cumple | `HallazgoController.java:65-66` (`investigador_campo`, `cientifico_alto_rango`) · `DetalleHallazgo.jsx:141` · `RegistroConUbicacionTest.java:88`, `HallazgoServiceTest.java:186` | La restricción de roles no tiene prueba HTTP (REQ-R-09). |
| HU-03 | c.2 justificación obligatoria | Cumple | No cumple → Cumple | `HallazgoService.java:132`, `VersionHallazgo` (Builder) y CHECK en base (`V20261104_1000__registro_hallazgo.sql:96-97`) · `HallazgoServiceTest.java:173`, `MigracionesRegistroTest.java:64` | Nada. |
| HU-04 | c.1 custodio investigador registrado y activo | Cumple | No cumple → Cumple | `EquipoTrabajoService.java:50-56` vía `GatewayCustodioAdapter.java:25` · `Equipos.jsx:65` · `EquipoTrabajoServiceTest.java:75,103` | Ver RF6: el alta sin custodio. |
| HU-04 | c.2 estado "En Expedición" con ID del investigador | Cumple | No cumple → Cumple | Estado `ASIGNADO` con CHECK que exige custodio (`V20261115_1800__crear_tablas_equipos.sql:16-20`); etiqueta "En expedición" en `WI/componentes/ui.jsx:113` · `EquipoTrabajoServiceTest.java:59` | El valor persistido es `ASIGNADO`, no "En Expedición" (REQ-R-15, Bajo). |
| HU-04 | c.3 vista para buscar y filtrar equipos por custodio | Cumple | No cumple → Cumple | `EquipoTrabajoController.java:52-66` · `Equipos.jsx:125,142` · `EquipoTrabajoServiceTest.java:115`, `WI/App.test.jsx:64` | La lista de investigadores se limita a 50 (`Equipos.jsx:12`, REQ-R-17). |
| HU-05 | c.1 auditoría con valor previo, nuevo, justificación, usuario y fecha | Cumple | No cumple → Cumple | `HallazgoService.java:175,199-236` · `HistorialController.java:40-44` · `RegistroConUbicacionTest.java:111` | Nada. |
| HU-05 | c.2 bloqueo físico de UPDATE y DELETE | **Parcial** | No cumple → Parcial | Solo por permisos y solo si se corre el script manual (`deploy/aiven/03-...sql.example:31-37`); `MigracionesRegistroTest.java:85` lo demuestra con un usuario creado a mano | Por defecto el usuario de la app tiene `GRANT ALL` (`01-esquemas-y-usuarios.sh:25`); el bloqueo no es automático ni se prueba contra la app real (REQ-R-02). |
| HU-05 | c.3 herramienta de comparación que resalta diferencias | **Parcial** | No cumple → Parcial | `HistorialController.java:52-57` · `Historial.jsx:65-92` · `HistorialServiceTest.java:47,65` | La lógica está probada; el resaltado en pantalla no (REQ-R-06). |
| HU-06 | c.1 buscar por nombre, familia o departamento | Cumple | Cumple (igual) | `FichasPublicasController.java:43-57` · `WP/paginas/Inicio.jsx` · `BusquedaFichasIntegracionTest`, `Inicio.test.jsx` | Nada. |
| HU-06 | c.2 ocultar coordenadas de especie en peligro | Cumple | Cumple (igual) | `SinCoordenadasIntegracionTest`, `Ficha.test.jsx:48` | Nada. |
| HU-06 | c.3 menos de 2 s con carga pesada paralela | **Parcial** | Parcial → Parcial (más evidencia) | Hay medición de p95 14 ms (1 usuario) y 59 ms (10 usuarios) con 10.000 fichas (`docs/equipo/07-reporte-qa.md:702-715`, `pruebas-carga/resultados/hu06-*.json`) | La medición es local, sin gateway y **sin carga científica concurrente**; el par `CIENTIFICA=0/1` quedó solo en humo (`07-reporte-qa.md:269`). Falta contra el despliegue real (REQ-R-08). |
| HU-07 | c.1 marcadores solo con rol alto rango | Cumple | Parcial → Cumple | `MapaUbicacionController.java:26-27` · `WI/App.jsx:104`, `PaginaMapa.jsx` · `MapaApiTest`, `PaginaMapa.test.jsx`, `WI/App.test.jsx:81` | Nada. |
| HU-07 | c.2 validación central en cada petición | Cumple | Parcial → Cumple | `PoliticaAccesoUbicacion` · `PoliticaAccesoUbicacionTest`, `SeguridadEndpointsTest` | Nada. |
| HU-07 | c.3 descifrado en memoria solo para aprobados | Cumple | Parcial → Cumple | `ServicioDescifrado` · `ServicioDescifradoTest`, `QaCifradoYReposoTest` | Nada. |
| HU-08 | c.1 API Key en encabezado | Cumple | Cumple (igual) | `GW/controller/ApiExternaController.java:24` · `ApiKeyRateLimitFilterTest.java:103`, `GatewayIntegracionTest.java:186` | Nada. |
| HU-08 | c.2 429 al exceder el límite | Cumple | Cumple (igual) | `GatewayIntegracionTest.java:245-253` | Nada. |
| HU-08 | c.3 URLs estables ante migración | Cumple | Cumple (igual) | `RutasGateway.PREFIJO_API_EXTERNA`, rutas por variable de entorno (`api-gateway/src/main/resources/application.yml:78-80`) · `ProxyServiceTest` | Nada. |
| HU-09 | c.1 generación asíncrona | Cumple | No cumple → Cumple | `SolicitudesReporteController.java:54-70` (202 + cola) · `Reportes.jsx:92` · `SolicitudesReporteIntegracionTest.java:125` (MySQL y RabbitMQ reales) | Nada. |
| HU-09 | c.2 no sube más de 10 % el registro diario | **Parcial** | No cumple → Parcial | Aislamiento por diseño: base `reportes` propia alimentada por eventos, cola dedicada | No hay medición: ningún script de `pruebas-carga/k6/` ejecuta reportes mientras mide el registro (REQ-R-07). |
| HU-09 | c.3 descarga PDF y CSV | Cumple | No cumple → Cumple | `SolicitudesReporteController.java:84-94` · `Reportes.jsx:60-61` · `SolicitudesReporteIntegracionTest.java:144-156`, `PdfPresionTest.java:27` | Nada. |
| HU-10 | c.1 exactamente un rol del catálogo | Cumple | Parcial → Cumple | `UsuariosController.java:58` · `Usuarios.jsx:31` · `KeycloakAdminAdapterIntegracionTest.java:124`, Bruno `gateway/23` | Nada. |
| HU-10 | c.2 sin rol solo ve el portal público | Cumple | Parcial → Cumple | `MatrizAccesoGatewayTest`, `WI/App.test.jsx:94` (menú vacío) | Nada. |
| HU-10 | c.3 cambio de rol desde la siguiente petición | Cumple | Parcial → Cumple | `UsuariosController.java:66` · `Usuarios.jsx:106` · `GatewayIntegracionTest.java:362` | Nada. |
| HU-11 | c.1 rechazo sin sesión válida | Cumple | Cumple (igual) | `GatewaySecurityTest`, `SeguridadEndpointsTest`, `WI/App.test.jsx` | Nada. |
| HU-11 | c.2 bloqueo tras 3 intentos | Cumple | Cumple (igual) | `infra/keycloak/realm-biohub.json:13-16` · `KeycloakAdminAdapterIntegracionTest.java:214` | Nada. |
| HU-11 | c.3 ID del usuario en cada carga, modificación o reclasificación | Cumple | No cumple → Cumple | `HallazgoController.java:90-93` (guarda `preferred_username`, D59), `VersionHallazgo.autor`, `CambioAuditoria.autor`, `subido_por` en evidencias · `HallazgoServiceTest.java:71,186` | Guarda el nombre de usuario, no el `sub` (REQ-R-12). |
| HU-12 | c.1 API Key única por consumidor | Cumple | Parcial → Cumple | `GW/controller/ConsumidoresController.java:52,80` · `ApiKeys.jsx:13,72` · `ServicioApiKeysTest`, `GeneradorApiKeyTest` | Nada. |
| HU-12 | c.2 revocación inmediata | Cumple | Parcial → Cumple | `ConsumidoresController.java:95` · `ApiKeys.jsx:94` · `GatewayIntegracionTest.java:186,326` | Nada. |
| HU-12 | c.3 401 sin API Key | Cumple | Cumple (igual) | `ApiKeyRateLimitFilterTest.java:103`, `GatewayIntegracionTest.java:319-321` | Nada. |
| HU-13 | c.1 al menos dos filtros combinados | Cumple | Cumple (igual) | `CI/ubicacion/controller/BusquedaHallazgosController.java:26-27` · `WI/App.jsx:103`, `PaginaBusqueda.jsx` · `BusquedaHallazgosApiTest`, `PaginaBusqueda.test.jsx` | Nada. |
| HU-13 | c.2 coordenadas solo según rol | Cumple | Cumple (igual) | `BusquedaHallazgosApiTest` (alto rango sí, demás no) | Nada. |
| HU-13 | c.3 menos de 3 s con volumen alto | Cumple | Parcial → Cumple | `CIT/ubicacion/rendimiento/BusquedaInterna10kRendimientoTest.java:81-82,195`: p95 máximo de 22 ms con 10.000 hallazgos (`07-reporte-qa.md:717-734`) | La prueba está apagada por defecto (`-Drendimiento=true`) y no corre en CI (REQ-R-18). |

Conteo de criterios: **Cumple 32, Parcial 6** (HU-01 c.3, HU-02 c.2, HU-05 c.2, HU-05 c.3, HU-06 c.3, HU-09 c.2), No cumple 0.

## 4. Resumen por HU

| HU | Puntos | 08-11 | 19-11 | Criterios |
|---|---|---|---|---|
| HU-01 | 5 | No cumple | **Parcial** | 2/3 |
| HU-02 | 8 | No cumple | **Parcial** | 2/3 |
| HU-03 | 3 | No cumple | Cumple | 2/2 |
| HU-04 | 3 | No cumple | Cumple | 3/3 |
| HU-05 | 5 | No cumple | **Parcial** | 1/3 |
| HU-06 | 5 | Parcial | **Parcial** | 2/3 |
| HU-07 | 8 | Parcial | Cumple | 3/3 |
| HU-08 | 8 | Cumple | Cumple | 3/3 |
| HU-09 | 13 | No cumple | **Parcial** | 2/3 |
| HU-10 | 5 | Parcial | Cumple | 3/3 |
| HU-11 | 3 | Parcial | Cumple | 3/3 |
| HU-12 | 5 | Parcial | Cumple | 3/3 |
| HU-13 | 5 | Parcial | Cumple | 3/3 |

Total HU: **Cumple 8** (40 puntos), **Parcial 5** (36 puntos), No cumple 0.

## 5. Hallazgos

### REQ-R-01 | Alto | `CI/registro/service/FichaPlantillaService.java:95`, `HallazgoService.java:92`
- **Problema:** `validarValores` está probado en `FichaPlantillaServiceTest.java:97-133` pero nadie lo llama. El registro y la reclasificación guardan cualquier mapa de `atributos` sin compararlo con la plantilla, y `plantilla_version` del hallazgo queda siempre en `null`.
- **Por qué importa:** HU-02 c.2 pide validar dinámicamente; hoy la validación es solo del navegador y se salta con una llamada directa al API. Además no se puede saber con qué esquema se guardó cada registro (HU-02 c.3, RF3).
- **Corrección:** inyectar `FichaPlantillaService` en `HallazgoService.registrar` y `reclasificar`, llamar a `validarValores`, guardar la versión de plantilla usada y añadir una prueba HTTP de "atributo inventado da 400".
- **Esfuerzo:** S.

### REQ-R-02 | Alto | `deploy/aiven/03-registro-historial-append-only.sql.example:31-37`, `infra/mysql/transaccional/01-esquemas-y-usuarios.sh:25`, `CI/registro/domain/VersionHallazgo.java`, `CambioAuditoria.java`
- **Problema:** el rechazo físico de UPDATE y DELETE sobre el historial (RNF10, ASR7, HU-05 c.2, RF7) solo existe si alguien ejecuta a mano un script `.example` como administrador. El script de arranque da `GRANT ALL` y las entidades JPA no son `@Immutable`. La única prueba (`MigracionesRegistroTest.java:85`) crea ella misma un usuario con los permisos del script.
- **Por qué importa:** el SRS pide rechazo "sin excepción de rol" y es un ASR. En una demostración local el historial es modificable con la credencial de la app.
- **Corrección:** aplicar el script de forma automática en el arranque de cada entorno (init de MySQL y paso de despliegue), marcar `@Immutable` las dos entidades y añadir una prueba que corra la aplicación con ese usuario y confirme el rechazo.
- **Esfuerzo:** M.

### REQ-R-03 | Medio | `CI/registro/service/TipoArchivoEvidencia.java:19-24`, `EvidenciaHallazgo.java:145`
- **Problema:** RF1 y el enunciado piden "archivos de datos asociados" (rutas GPS, muestras de ADN). Solo se admiten fotos JPEG y PNG y audio WAV, MP3, OGG y FLAC; no existe el tipo `DATOS`.
- **Por qué importa:** el ejemplo del ave migratoria (ruta de GPS y muestra de ADN) no se puede adjuntar. Es el único texto de RF1 que queda sin cubrir.
- **Corrección:** admitir un conjunto cerrado de formatos de datos (por ejemplo CSV, GPX, JSON) con tipo `DATOS`, con límite de tamaño y sin ejecutables; o dejar por escrito que se excluyen y por qué.
- **Esfuerzo:** M.

### REQ-R-04 | Medio | `EQ/service/EquipoTrabajoService.java:27-41`, `EquipoTrabajoController.java:39-50,88-92`
- **Problema:** el alta de un equipo no recibe custodio; queda `DISPONIBLE` sin investigador. El custodio se valida solo en la asignación posterior.
- **Por qué importa:** RF6 dice "obligatoriamente cada equipo físico registrado" y HU-04 dice "Al registrar o asignar". Un equipo en inventario sin responsable es lo que el enunciado quiere evitar ("para que no se pierda").
- **Corrección:** o bien exigir `custodioId` en el alta (validarlo y asignar en la misma transacción), o bien confirmar con el docente que "DISPONIBLE en bodega" es válido y anotarlo en el SRS.
- **Esfuerzo:** S.

### REQ-R-05 | Medio | `WI/paginas/NuevoHallazgo.jsx:103-106`
- **Problema:** el mensaje "Campos obligatorios incompletos" (HU-01 c.3) está solo en la pantalla y ninguna prueba lo verifica; el backend responde con mensajes por campo.
- **Por qué importa:** es el único criterio de HU-01 que queda sin respaldo automático.
- **Corrección:** prueba Vitest que envíe el formulario vacío y espere el texto; opcionalmente que el backend lo incluya en el `title` del 400.
- **Esfuerzo:** S.

### REQ-R-06 | Medio | `WI/paginas/` (NuevoHallazgo, DetalleHallazgo, Historial, Plantillas, Usuarios, ApiKeys, Reportes, Equipos)
- **Problema:** de las 8 pantallas nuevas solo `Equipos` tiene una prueba (`WI/App.test.jsx:64`, que además solo comprueba que lista vacío). Las otras 7 no tienen ninguna; las pruebas de `WI/ubicacion/` cubren solo mapa y búsqueda.
- **Por qué importa:** son las pantallas que implementan HU-01, 02, 03, 05, 09, 10 y 12. El resaltado del comparador (HU-05 c.3) y el formulario dinámico (HU-02 c.2) no tienen respaldo.
- **Corrección:** una prueba de humo por pantalla (carga, éxito y error) con `api` simulada; empezar por Historial, NuevoHallazgo y Plantillas.
- **Esfuerzo:** M.

### REQ-R-07 | Medio | `pruebas-carga/k6/` (no hay script de reportes)
- **Problema:** HU-09 c.2 y RNF3/ASR3 piden demostrar que el reporte no sube más de 10 % el tiempo de registro. No existe ninguna medición; solo `registro-hallazgo.js` y `carga-cientifica-simultanea.js`, que no lanzan reportes.
- **Por qué importa:** es el criterio de aceptación que justifica la separación de bases (ASR3) y la historia más grande (13 puntos).
- **Corrección:** k6 que mida p95 del registro sin reporte y con un reporte sobre miles de hallazgos corriendo, y compare con el 10 %. Registrar el resultado en `07-reporte-qa.md`.
- **Esfuerzo:** M.

### REQ-R-08 | Medio | `docs/equipo/07-reporte-qa.md:269,702-715`
- **Problema:** HU-06 c.3 se midió sin carga científica concurrente, sin gateway y en local; el par `CIENTIFICA=0/1` sigue en humo.
- **Por qué importa:** el criterio dice "incluso si hay procesos paralelos de carga pesada".
- **Corrección:** correr `carga-cientifica-simultanea.js` con `CIENTIFICA=1` a escala completa contra `develop` y registrar el p95 público.
- **Esfuerzo:** M.

### REQ-R-09 | Medio | `CI/registro/controller/` (sin prueba HTTP de `HallazgoController`, `HistorialController`, `FichaPlantillaController`)
- **Problema:** las reglas de rol de registro (por ejemplo que auditor y administrador no puedan reclasificar, HU-03 c.1; que el investigador no vea el historial) no tienen prueba MockMvc. `SeguridadEndpointsTest.java:196` solo toca `/hallazgos` para comprobar un 404.
- **Por qué importa:** HU-03 c.1 y HU-05 dependen de estos roles y el documento de trazabilidad los da por verificados "de punta a punta" sin prueba automatizada en el repositorio.
- **Corrección:** ampliar la matriz de `SeguridadEndpointsTest` con los 3 controladores nuevos (401, 403 y 2xx por rol).
- **Esfuerzo:** S.

### REQ-R-10 | Medio | `RP/service/CalculadorIndicePresion.java:14-17`
- **Problema:** RF16 pide detectar zonas de deforestación; el índice se calcula con la proporción de hallazgos en peligro y su crecimiento, porque no hay datos de cobertura forestal.
- **Por qué importa:** si el docente lee "deforestación" literalmente, el reporte no la mide. El SRS acepta "menor escala" pero no cambiar la medida.
- **Corrección:** dejar escrito en el SRS o en la sustentación que es un sustituto, con la fórmula y los umbrales (ya están en el comentario de la clase).
- **Esfuerzo:** S.

### REQ-R-11 | Bajo | `HistorialController.java:52-57`
- **Problema:** el enunciado pide "saber qué se creía en enero y compararlo con diciembre"; el comparador solo recibe números de versión.
- **Por qué importa:** RF8 habla de periodos de tiempo. Las versiones tienen fecha, pero el auditor tiene que adivinar qué número corresponde a enero.
- **Corrección:** parámetro `?fecha=` que devuelva la versión vigente en esa fecha, y selector de fecha en `Historial.jsx`.
- **Esfuerzo:** S.

### REQ-R-12 | Bajo | `HallazgoController.java:90-93`, `CambioAuditoria.java`
- **Problema:** RF9 dice "qué usuario autorizó cada modificación". El sistema guarda quién la ejecutó (nombre de usuario). No hay paso de autorización por otro rol, y se guarda `preferred_username` en vez del `sub` que menciona el SRS ("ID del usuario").
- **Por qué importa:** ambigüedad de interpretación que conviene cerrar antes de la sustentación.
- **Corrección:** confirmar con el docente que ejecutor = autorizador; anotarlo como decisión (D59 ya cubre el nombre de usuario).
- **Esfuerzo:** S.

### REQ-R-13 | Bajo | alcance de más (lista)
El enunciado pide "no inventen alcance de más". Elementos sin respaldo en SRS o enunciado:
- **Fotos públicas por especie y catálogo por especie (D65):** `consulta-publica/.../controller/EspeciesPublicasController.java:46-69`, `CI/registro/controller/FotoPublicaController.java:33`, `EvidenciaController.java:88` (`PUT .../publica`), `WP/components/FotoEspecie.jsx`, `TarjetaEspecie.jsx`. El SRS describe fichas, no fotos públicas. Además `docs/equipo/01-requisitos.md:736` las deja en "Won't". Es el añadido más grande y abre superficie de exposición de datos.
- **Contenido decorativo:** carrusel de pinturas y textos históricos (`WI/paginas/Inicio.jsx:41-42`, `WI/componentes/pinturas.js`, `infra/keycloak/themes/biohub/login/resources/js/fondos.js`). Sin requisito.
- **Equipos:** estados `MANTENIMIENTO` y `BAJA` y categorías "GPS" y "Otro" (`Equipos.jsx:6-7`, `V20261115_1800__crear_tablas_equipos.sql:16`). El SRS solo pide asociar al custodio.
- **Reportes síncronos duplicados:** `RP/controller/ReportesController.java:34-70` (`/resumen`, `/hallazgos`, `/exportar/csv`, `/exportar/pdf`); ninguna pantalla los usa (REQ-R-14).
- **Menores:** expiración de API Keys (`ApiKeys.jsx:72`), sugerencias de especies (`CI/registro/controller/EspeciesController.java:26-37`), endpoint de re-cifrado (`CI/ubicacion/controller/RecifradoController.java:25`, defendible por RNF9/KMS), `EventosPruebaController` (`CI/eventos/controller/EventosPruebaController.java:32`, endpoint de prueba que no debería estar en producción). Las teselas del gateway (`GW/controller/TeselasController.java:34`) sostienen HU-07 y se pueden defender.
- **Corrección:** declarar en la sustentación qué es extra y por qué; apagar lo que no se pueda defender (fotos públicas, reportes síncronos, endpoint de prueba).
- **Esfuerzo:** S.

### REQ-R-14 | Bajo | `RP/controller/ReportesController.java:34-70`
- **Problema:** estos endpoints calculan el resumen y los archivos en la petición, sin cola. Contradicen la idea de HU-09 c.1 si alguien los usa, y la pantalla no los usa.
- **Por qué importa:** son un camino que sí bloquea y consulta la base analítica en línea; no tiene respaldo de requisito.
- **Corrección:** eliminarlos o restringirlos; mantener solo `/api/reportes/solicitudes`.
- **Esfuerzo:** S.

### REQ-R-15 | Bajo | `EQ/domain/EstadoEquipo.java:5`
- **Problema:** HU-04 c.2 dice estado "En Expedición"; el valor guardado es `ASIGNADO` y "En expedición" es solo la etiqueta de pantalla (`WI/componentes/ui.jsx:113`).
- **Por qué importa:** un auditor que lea la base no encuentra el texto del criterio.
- **Corrección:** renombrar el valor o documentar la equivalencia en el contrato.
- **Esfuerzo:** S.

### REQ-R-16 | Bajo | `docs/equipo/01-requisitos.md`
- **Problema:** el documento solo cubre la parte de Juanita y sigue desactualizado (por ejemplo `:736`). No refleja el MVP completo ni D65.
- **Por qué importa:** es el documento de requisitos del equipo y contradice al código.
- **Corrección:** reducirlo a un índice que remita al SRS y a `trazabilidad.md`, o actualizarlo.
- **Esfuerzo:** S.

### REQ-R-17 | Bajo | `WI/paginas/Equipos.jsx:12`
- **Problema:** la lista de investigadores para custodio y filtro pide `size=50`; con más de 50 usuarios, los restantes no aparecen.
- **Corrección:** buscador o paginación sobre `/api/gateway/usuarios`.
- **Esfuerzo:** S.

### REQ-R-18 | Bajo | `CIT/ubicacion/rendimiento/BusquedaInterna10kRendimientoTest.java:81-82`
- **Problema:** la prueba de HU-13 c.3 solo corre con `-Drendimiento=true`; la CI normal la omite.
- **Corrección:** correrla en un job nocturno o manual de la CI y enlazar el resultado.
- **Esfuerzo:** S.

### Requisitos del enunciado que no están en el SRS
- **"Zonas remotas sin poder subir hallazgos"** (enunciado, "La consulta viral"): se resuelve con el aislamiento de servicios (RNF2); no hay carga diferida ni modo sin conexión y el SRS tampoco lo pide. No es un hueco real.
- **"Saber qué se creía en enero y compararlo con diciembre"**: el SRS lo reduce a RF8 por versiones. Ver REQ-R-11.
- **"Ver quién autorizó cada cambio"**: el SRS (RF9) lo conserva, pero no define autorización distinta de ejecución. Ver REQ-R-12.
- **"Organismos internacionales"** como usuarios: sin rol propio en el SRS; los cubren el portal público y la API externa. Sin acción.
- **"Rutas de GPS y muestra de ADN"** como datos de una ficha: el SRS los menciona como atributos (RF2) pero los tipos de atributo son solo texto, número, entero, booleano y fecha (`TipoAtributo.java`); ver REQ-R-03.
- No encontré requisitos del enunciado omitidos del SRS más allá de los anteriores.

## 6. Bien hecho

- **Cobertura RF completa:** ningún RF queda sin código; 16 de 19 cumplen con prueba, y los 3 Parcial tienen un hueco concreto y acotado.
- **Criterios trazables:** la mayoría de las pruebas citan el criterio en `@DisplayName` ("HU-01", "HU-03", "criterio 2"), lo que permite verificar cada uno.
- **Justificación obligatoria en tres capas:** servicio (`HallazgoService.java:132`), Builder de dominio y CHECK en la base (`V20261104_1000__registro_hallazgo.sql:96-97`), con prueba de la base (`MigracionesRegistroTest.java:64`).
- **Reportes asíncronos con pruebas reales:** `SolicitudesReporteIntegracionTest` usa MySQL y RabbitMQ reales y recorre 202, estado, CSV y PDF; hay tarea mensual idempotente (`:205`).
- **Equipos:** validación del custodio contra el gateway con pruebas de custodio inactivo y de no-investigador; el CHECK de la base impide `ASIGNADO` sin custodio.
- **Pantallas montadas por rol:** `WI/App.jsx` ya enruta todo, el menú y la pantalla "esta sala no es para tu rol" salen de una sola matriz (`WI/auth/permisos.js`) con prueba (`WI/App.test.jsx:37-97`). RF12, HU-07 y HU-13 dejaron de ser "backend sin pantalla".
- **Rendimiento medido:** HU-13 c.3 con 10.000 hallazgos y HU-06 c.3 con 10.000 fichas, ambos con método, 3 corridas y mediana documentados.
- **Seguridad por defecto:** `denyAll` ahora en los 5 servicios (`SecurityConfig.java` de equipos `:37` y reportes `:37` ya no usan `authenticated()`), lo que cierra el pendiente SEC-02 del informe anterior.
- **Coordenadas:** cero coordenadas en eventos, base pública y reportes (D14), con pruebas y `ArchUnit`.

## 7. Preguntas para el usuario (cambian el alcance)

1. RF6/HU-04: ¿un equipo puede existir en inventario sin custodio (DISPONIBLE) o el alta debe exigirlo?
2. RF1: ¿"archivos de datos asociados" incluye CSV/GPX/ADN? Si sí, hay que añadir el tipo `DATOS`.
3. RF9: ¿"autorizó" es el ejecutor del cambio o hace falta un paso de aprobación por el auditor?
4. D65 (fotos públicas por especie): ¿se mantiene como extra declarado o se retira por la regla de no inventar alcance?
5. RF16: ¿el sustituto de "deforestación" por presión sobre especies en peligro es aceptable para la sustentación?
