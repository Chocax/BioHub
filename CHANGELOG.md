# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/).

## [Unreleased]

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
