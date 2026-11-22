# Pendientes por persona (revisión 2026-11-19)

Reparto según la tabla de responsables del README y el autor del código afectado. IDs: ver `INFORME.md` y los informes de área en esta carpeta.
Prioridad: 🔴 antes de la sustentación · 🟡 importante · ⚪ si sobra tiempo.

## Juanita (`feature/ubicacion-consulta-publica`: ubicación, consulta pública, web-publica)
- 🔴 **Fotos públicas D65 (ARQ-R-1), en conjunto con Santiago:** que consulta-pública deje de llamar al científico por cada foto. Guardar una miniatura ya procesada en el lado de lectura cuando se publica la foto (evento) o, como mínimo, circuit breaker + caché más grande + ADR.
- 🔴 **Mediciones k6 con D42 (ARQ-R-2):** ASR1 (consulta viral, incluida la portada nueva `/api/publica/especies`) y ASR4 (par `CIENTIFICA=0/1`), con metadatos.
- 🟡 **Unificar el detector de coordenadas con Hernando (BE-R-1, SEC-R-2):** el `DetectorCoordenadas` es el estricto; registro debe usar el mismo.
- 🟡 El monitor de degradación no mide `/api/publica/especies`; cada evento vacía 3 cachés completas (invalidar solo lo afectado).
- ⚪ `FotoEspecie` trata 429/5xx como "sin foto" (FE-R-1); la portada carga 4 fondos a la vez (FE-R-5); E2E intermitente `portal.spec.js:110` (QA-R-3); `?familia=` vacío (QA-R-5); `ANALYZE TABLE` pendiente (DB-J22-1).

## Santiago (`feature/seguridad-acceso`: gateway, Keycloak, web-interna, infraestructura compartida)
- 🔴 **GPS en evidencias (SEC-R-1, autor del `LimpiadorMetadatos`):** limpiar audio (WAV/GUANO, MP3, OGG, FLAC) y JPEG completos (segunda imagen MPF, trailer Motion Photo), con pruebas. Es lo que tiene a seguridad en "Rechazado".
- 🔴 **Fotos D65 (ARQ-R-1), con Juanita:** él lo diseñó; escribir el ADR o rediseñar. Agregar límite de píxeles al decodificar.
- 🔴 **Append-only también en local (DB-R-2, ARQ-R-7):** en `infra/mysql/transaccional/01-esquemas-y-usuarios.sh` separar usuario de migración y de aplicación, sin UPDATE/DELETE sobre `hallazgo_version` y `hallazgo_auditoria`. Lo mismo para los demás usuarios con `ALL PRIVILEGES`.
- 🟡 **DevOps (OPS-R-1, 2, 3, 6, 7):** `levantar.sh` para Linux/macOS, README actualizado (dice "esqueleto" y "7 contenedores"), `.env.example` completo, semilla para Bruno documentada, aviso cuando `rabbitmq-usuarios` omite usuarios, y plan de despliegue de la Entrega 3.
- 🟡 **web-interna:** tests de las 7 pantallas nuevas (FE-R-6), CSP (FE-R-3), errores en las descargas de reportes (FE-R-2), roles al refrescar el token (FE-R-4).
- ⚪ ADR del gateway como punto único de falla; `levantar.ps1:290` muestra la contraseña; imágenes de hasta 783 KB.

## Hernando (`feature/registro-cientifico`: registro, fichas, historial, eventos)
- 🔴 **Hallazgo guardado sin evento (BE-R-1):** usar el mismo detector que el publicador y capturar el error, para no dar 500 ni duplicar al reintentar.
- 🔴 **Validar atributos contra la plantilla (BE-R-4, HU-02):** llamar a `FichaPlantillaService.validarValores` en `HallazgoService` y guardar `plantillaVersion`.
- 🔴 **NPE con nulos (BE-R-3):** `Map.copyOf` en `VersionHallazgo.getSnapshot` y `Hallazgo.getAtributos`; prueba con hallazgo sin nombre común ni descripción.
- 🟡 **Eventos perdidos (BE-R-2, DB-R-1):** primero un endpoint de republicación desde `hallazgo`; después outbox.
- 🟡 **Departamento normalizado (BE-R-5):** validarlo contra el catálogo antes de guardar y publicar (con Nicolás).
- 🟡 Reclasificación concurrente da 500 en vez de 409 (falta `@Version`); `nombreCientifico` acepta `<script>` (QA-R-1); JSON malformado sin problem+json (QA-R-2); el snapshot del historial no guarda atributos.
- 🟡 Pruebas MockMvc de la matriz de roles en hallazgos/historial/fichas; prueba del mensaje de HU-01 c.3; medir ASR2 (`k6/registro-hallazgo.js` sigue "PENDIENTE").
- ⚪ RF1 "archivos de datos" (solo hay JPEG, PNG y audio); decidir si cualquier investigador puede reclasificar hallazgos ajenos.

## Nicolás (`feature/reportes-equipos`: equipos y reportes)
- 🔴 **Medir RNF3/ASR3:** registro sin degradarse más de 10 % con un reporte pesado corriendo (HU-09 c.2).
- 🟡 **Departamentos partidos en reportes (BE-R-5, QA-R-4):** normalizar al consumir el evento; llenar `region`.
- 🟡 **Inyección de fórmulas en CSV (SEC-R-3)** en `/api/reportes/exportar/csv`.
- 🟡 **Equipos:** custodio obligatorio en el alta (RF6/HU-04), `@Version` contra asignaciones concurrentes, `BAJA` terminal, historial de custodia, la llamada HTTP al gateway fuera de `@Transactional` y con timeouts.
- 🟡 Solicitudes de reporte huérfanas en PENDIENTE/EN_PROCESO; reintento del reporte mensual programado.
- ⚪ Exportaciones síncronas que cargan toda la tabla (o quitarlas: ninguna pantalla las usa); `LOWER()/UPPER()` que anulan índices.

## De todo el grupo
- Decidir y documentar el **alcance de más** (catálogo y fotos por especie, carrusel, estados extra de equipos): ADR o retirarlo.
- Preguntas abiertas del SRS: "quién **autorizó**" un cambio (hoy solo se guarda quién lo hizo), "qué se creía en enero" (el comparador usa números de versión) y RF16 (presión sobre especies en peligro como sustituto de deforestación).
- Actualizar `01-requisitos.md` (solo cubre la parte de Juanita) y `05-modelo-datos.md`.
- **Volver a clonar** el repositorio por la reescritura del historial.
