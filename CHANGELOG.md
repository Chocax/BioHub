# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/).

## [Unreleased]

### Added (cóndor y README, 2026-11-24)
- El cóndor andino entra a la colección: la lámina IX del *Recueil d'observations de zoologie* de Humboldt y Bonpland (1811, dominio público) es la obra número 20 del carrusel interno, el cielo del portal y el fondo del login.
- En el login, dos cóndores (silueta propia en la pose heráldica del escudo de Bogotá, `condor.svg`) planean en círculos detrás de la tarjeta; se ocultan en pantallas pequeñas y con movimiento reducido.
- README nuevo: cabecera animada, stack como cartelas, fichas propias en vez de insignias, capturas, arquitectura, concepto artístico y reparto del equipo.

### Fixed (2026-11-24)
- CI de web-publica: el portal volvía a guardar algo en el navegador (el orden de las pinturas), lo que rompe WP-08. Ahora baraja sin guardar nada; la web interna y el login sí recuerdan la primera obra.

### Changed (equipos, 2026-11-24)
- Web interna al día con el PR #22: el alta de un equipo pide el custodio (obligatorio), solo se asigna un equipo disponible y cada equipo muestra su historial de custodia.

### Changed (pinturas, 2026-11-23)
- El carrusel del inicio interno, el cielo del portal y el fondo del login muestran las mismas 19 obras de pantalla completa, en orden aleatorio, y nunca arrancan con la misma de la visita anterior. Las tres verticales y pequeñas (Mutisia, Icononzo, catedral de Santa Marta) quedan en las salas. Una prueba vigila que las tres listas coincidan.
- El carrusel interno monta solo la pintura actual y sus vecinas, y cambia los puntos por una línea de avance.

### Added (obras, 2026-11-22)
- Seis obras nuevas de dominio público (CC0, Cooper Hewitt): Frederic E. Church en Colombia y los Andes. Entran a las pinturas al azar de las salas, a las tarjetas y al carrusel del inicio interno (dos diapositivas nuevas), al fondo del inicio de sesión (12 obras) y al cielo del portal (7 obras).
- El carrusel del inicio interno respeta el encuadre de cada obra.
- Ocho obras de historia de Colombia (Comisión Corográfica, E. W. Mark, Expedición Botánica, Humboldt), dominio público, en las salas, el carrusel, el login (15 obras) y el cielo del portal (8 obras).

### Security (revisión del equipo, 2026-11-21)
- Evidencias sin ubicación también en audios y en JPEG con datos pegados: el JPEG se recorre completo y se corta en el fin de imagen; WAV, MP3, FLAC y OGG se reconstruyen solo con el sonido.
- MySQL local con los mismos permisos que Aiven: usuario `*_app` y usuario `*_migr` por base, sin `ALL PRIVILEGES`, e historial de registro append-only también en local.
- Web interna con Content-Security-Policy en el build.

### Added (revisión del equipo, 2026-11-21)
- `levantar.sh` para Linux y macOS.
- ADR completo de D65 (`docs/equipo/adr/D65-fotos-publicas.md`) y plan de despliegue de la Entrega 3 (`docs/equipo/12-plan-despliegue-entrega-3.md`).
- Pruebas de las 8 salas de la web interna.

### Changed (revisión del equipo, 2026-11-21)
- `docker-compose.yml`: las 4 bases usan `infra/mysql/initdb/01-usuarios.sh` (se elimina `infra/mysql/transaccional/`). Nuevas variables `<P>_MIGR_USER/PASSWORD` en `.env`; `levantar` las genera si faltan.
- README al día y `.env.example` con todas las variables.

### Removed
- Retirada la VPN de malla anterior; MySQL pasa a Aiven. Se eliminan `deploy/` (VMs KVM, scripts de despliegue, ACL), la guía de la VPN y las VMs `biohub-backend`/`biohub-datos`.

### Added
- Evento de prueba de RabbitMQ: `POST /api/cientifico/eventos/prueba`, `GET /api/publica/eventos/recibidos` y `GET /api/reportes/eventos/recibidos`.

### Added (acceso y gateway)
- API externa `/api/v1/externa/fichas/**` con API Key y límite por consumidor; límite por IP en el portal; administración de consumidores, keys y usuarios en `/api/gateway/**`; denegación por defecto y control por rol en el gateway; revocación inmediata de tokens tras un cambio de rol; usuarios RabbitMQ por servicio y memoria acotada en Redis.

### Changed (acceso y gateway)
- Realm: bloqueo a los 3 intentos, cliente `biohub-gateway` y usuario `sinrol1`. Requiere `KEYCLOAK_GATEWAY_CLIENT_SECRET` en `.env` y reimportar el realm.

### Added (acceso y gateway, 2)
- Circuit breaker por servicio destino en el gateway: 503 inmediato con `Retry-After` cuando un servicio falla varias veces seguidas.

### Changed (acceso y gateway, 2)
- CI: acciones de GitHub en versiones que corren con Node 24.

### Fixed (acceso y gateway)
- El proxy dejaba vacíos los adjuntos multipart.

### Added (revisión antes del front)
- Evidencias de los hallazgos: subir, listar y descargar fotos y audios (`/api/cientifico/hallazgos/{id}/evidencias`). El formato se reconoce por el contenido y a las fotos se les quitan los metadatos con la ubicación GPS.
- Reporte mensual asíncrono de presión sobre los ecosistemas (`/api/reportes/solicitudes`): 202, proceso en segundo plano por RabbitMQ, índice por departamento con zonas de alerta, CSV y PDF, y un reporte automático cada mes.
- CI: pruebas de `servicio-equipos` y `servicio-reportes`.

### Changed (revisión antes del front)
- Solo `investigador_campo` y `cientifico_alto_rango` registran, reclasifican y editan plantillas. El administrador ya no; el investigador ahora puede reclasificar.
- Un equipo solo se asigna a un `investigador_campo` activo; los filtros de estado y custodio se combinan.
- El historial de hallazgos queda sin `UPDATE` ni `DELETE` en Aiven (script `03`, paso obligatorio).
- RabbitMQ: `reportes_mq` con permisos sobre su cola de solicitudes. Hay que volver a correr `rabbitmq-usuarios`.

### Fixed (revisión antes del front)
- Dos pruebas de integración de reportes que no arrancaban.

### Added (front)
- Web interna completa con el diseño "galería" (D64): inicio por rol, búsqueda y mapa, registrar hallazgo con atributos de la plantilla y evidencias, detalle, reclasificación, historial con comparación, plantillas, equipos, reportes con descarga de PDF y CSV, usuarios y roles, y API keys.
- Menú y salas según el rol; una sala ajena explica por qué en vez de fallar.

### Added (front, segunda revisión)
- `levantar.ps1` (y `levantar.cmd`): levanta Docker, los 5 servicios y las 2 webs con un comando, muestra cada paso con su puerto y al final los usuarios de prueba. `-Sembrar` crea datos de ejemplo con fotos de Wikimedia Commons; `-Estado` y `-Detener`.
- Catálogo por especie con foto en el portal y en la galería de fichas; listas desplegables de especies, familias e investigadores (D65).
- El investigador decide qué foto sale en el portal («Mostrar en el portal»); el portal la muestra en la tarjeta y en la ficha, con los otros registros de la especie.
- Mapa: leyenda de los marcadores, seleccionado con anillo, panel con foto y enlace a la ficha.
- PDF del reporte con el diseño de BioHub: encabezado, cifras, zonas en alerta, tabla con barras y niveles, y cómo leerlo.
- Inicio de sesión de Keycloak con el estilo de la web interna (tema `biohub`), favicon en las dos webs, iconos en menús y botones, pinturas que rotan con fundido y pie de página nuevo en el portal.

### Added (front, tercera revisión)
- Carrusel de inicio con flechas (y flechas del teclado) y dos diapositivas más; cada sala elige una pintura al azar de una colección más grande (Tequendama, temporada de lluvias, pasifloras).
- Usuarios y roles: más ancha, indicadores, filtros por rol y estado con conteos, colores por rol, inactivo en rojo, aviso que confirma cada cambio y resalta la fila.
- Cada sala muestra su nombre en serif gigante y vertical en el borde izquierdo, y una cartela con su número en el derecho (pantallas anchas).
- El inicio de sesión alterna cuatro pinturas de fondo; los fondos de las dos webs cambian cada 18 a 25 s con un fundido de 3 s.

### Fixed (front, tercera revisión)
- Los reportes generados antes del PDF nuevo se descargaban con el formato viejo: ahora PDF y CSV se arman al descargar desde el resumen.
- El CSV se abría en una sola columna en Excel en español: ahora usa `;` y coma decimal.
- Fondos pixelados en la web pública: se usaba la versión de 800 px de los Andes a pantalla completa.

### Fixed (front, segunda revisión)
- Al escribir el nombre de un atributo de una ficha se perdía el foco en cada letra.
- El panel de detalle del mapa salía sin márgenes.
- En Windows, MySQL de Docker no creaba sus usuarios (script de inicio con fin de línea CRLF) y `rabbitmq-usuarios` podía fallar si arrancaba antes que la API de RabbitMQ.

### Changed (front)
- Web pública rediseñada con cielo azul, blanco y verde, y la misma distribución que la interna.
- El ping de prueba de la web interna se reemplaza por las pantallas reales.
- `web-interna`: `Arnes.jsx` y `arnes.jsx` chocaban en Windows. El componente pasa a `ArnesUbicacion.jsx`.

### Added (acceso y gateway, 3)
- Proxy de teselas del mapa en el gateway (`GET /api/gateway/teselas/{z}/{x}/{y}.png`): OpenStreetMap deja de ver la IP del científico junto con el área de un hallazgo protegido (SEC-J-04). Exige JWT con rol, limita por usuario y guarda las teselas en memoria.
- Prueba de carga k6 de ASR8 (`pruebas-carga/k6/gateway-limite-api-externa.js`): un consumidor abusivo recibe 429 sin afectar a los demás, y una key revocada se rechaza desde la petición siguiente.

### Added (iteración 15)
- Perfiles Spring `local` (MySQL del docker-compose) y `aiven` (MySQL administrado con SSL y verificación del CA) en `servicio-cientifico` y `servicio-consulta-publica`; `.env.example` por servicio.

### Changed
- README: la sección "10b. Despliegue" describe la nueva topología (MySQL en Aiven; RabbitMQ, Redis y Keycloak en Docker local).
