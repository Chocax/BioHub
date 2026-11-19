# 11 – Seguridad y acceso: guía para el equipo y sus agentes

Punto de partida para quien vaya a tocar algo relacionado con seguridad, roles, API Keys o el gateway. Si usas un agente, pásale este archivo junto con la sección que te corresponde.

Detalle formal: decisiones en `03-arquitectura.md` (D45 a D51), contrato en `04-contrato-api.md` ("Parte de Santiago"), tablas en `05-modelo-datos.md` §17 y despliegue en `09-despliegue.md` §11.

## 1. Cómo entra una petición

Todo pasa por `api-gateway` (8080). Los servicios escuchan solo en `127.0.0.1`.

| Quién llega | Ruta | Se identifica con | Qué valida el gateway |
|---|---|---|---|
| Personal interno (`web-interna`) | `/api/{cientifico,equipos,reportes}/**` | JWT de Keycloak | Token válido y uno de los 5 roles |
| Visitante (`web-publica`) | `GET /api/publica/**` | Nada | 120 peticiones por minuto por IP |
| Universidad u ONG | `GET /api/v1/externa/fichas/**` | Cabecera `X-API-Key` | Key válida y 60 peticiones por minuto por consumidor |
| Administrador | `/api/gateway/{consumidores,keys,usuarios}/**` | JWT | Rol `administrador` |
| Mapa de `web-interna` | `GET /api/gateway/teselas/{z}/{x}/{y}.png` | JWT | Uno de los 5 roles y 600 teselas por minuto por usuario (§7) |

Lo que no aparece en esa tabla se deniega. El gateway hace el control grueso. **El control fino es de cada servicio**: qué rol puede usar cada endpoint.

## 2. Matriz de roles por capacidad

Estado a 2026-11-16: todas las rutas existen y coinciden con el código (cada servicio con `denyAll` y `@PreAuthorize`).

| Capacidad | Ruta | `investigador_campo` | `auditor_cientifico` | `cientifico_alto_rango` | `analista_ambiental` | `administrador` | Dueño |
|---|---|---|---|---|---|---|---|
| Buscar hallazgos sin coordenadas | `GET /api/cientifico/ubicacion/hallazgos` | Sí | Sí | Sí | Sí | Sí | Juanita |
| Ver mapa y coordenadas | `GET /api/cientifico/ubicacion/mapa` | No | No | Sí | No | No | Juanita |
| Re-cifrar | `POST /api/cientifico/ubicacion/recifrado` | No | No | No | No | Sí | Juanita |
| Registrar hallazgo | `POST /api/cientifico/hallazgos` | Sí | No | Sí | No | No | Hernando (D63) |
| Reclasificar | `PUT /api/cientifico/hallazgos/{id}` | Sí | No | Sí | No | No | Hernando (D63) |
| Configurar plantilla de ficha | `PUT /api/cientifico/fichas/{especie}/atributos` | Sí | No | Sí | No | No | Hernando (D63) |
| Subir evidencias | `POST /api/cientifico/hallazgos/{id}/evidencias` | Sí | No | Sí | No | No | D62 |
| Consultar hallazgo, plantilla y evidencias | `GET /api/cientifico/hallazgos/{id}`, `/fichas/{especie}/atributos`, `/hallazgos/{id}/evidencias/**` | Sí | Sí | Sí | No | Sí | Hernando |
| Ver historial y comparar | `GET /api/cientifico/hallazgos/{id}/{versiones,auditoria,comparar}` | No | Sí | Sí | No | Sí | Hernando |
| Inventario y asignación de equipos | `/api/equipos/**` | No | No | No | No | Sí | Nicolás |
| Reportes (consultas y mensual) | `/api/reportes/**` | No | No | No | Sí | Sí | Nicolás (D61) |
| API Keys y límites | `/api/gateway/consumidores/**`, `/keys/**` | No | No | No | No | Sí | Santiago |
| Usuarios y roles | `/api/gateway/usuarios/**` | No | No | No | No | Sí | Santiago |
| Fondo del mapa (teselas) | `GET /api/gateway/teselas/**` | Sí | Sí | Sí | Sí | Sí | Santiago |

`administrador` nunca recibe coordenadas (RF12) ni edita datos científicos.

## 3. Qué debe hacer cada integrante

### Todos, una vez, al traer estos cambios

1. Agrega al `.env` de la raíz las variables nuevas de `.env.example`. Sin `KEYCLOAK_GATEWAY_CLIENT_SECRET`, `docker compose up` responde `Falta en .env`. Generar el valor en bash: `echo "KEYCLOAK_GATEWAY_CLIENT_SECRET=$(openssl rand -hex 24)" >> .env`.
2. Reimporta el realm (borra los datos locales de Keycloak, que son solo de desarrollo):
   ```bash
   docker compose rm -sf keycloak && docker volume rm biohub_keycloak-data && docker compose up -d keycloak
   ```
3. Si reinicias `api-gateway`, su arranque aplica la migración nueva. Contra Aiven solo debe hacerlo el dueño del gateway.

### Hernando (`registro`) y Nicolás (`equipos` y `reportes`): estado al 2026-11-16

Lo que antes estaba como tarea de cada uno ya está en el código: rutas reales en la matriz, `denyAll` y `@PreAuthorize`
en los tres servicios, el custodio validado contra el gateway (y ahora exigido como `investigador_campo`), y los roles
que reclasifican (D63). El autor se guarda con `preferred_username`, que en este realm es un ID único e inmutable (D59).
Lo que se agregó para cerrar el SRS, con su dueño original como responsable de mantenerlo: evidencias (D62), reporte
mensual asíncrono con índice de presión (D61) e historial bloqueado en la base de datos (D60).

### Juanita (`ubicacion`, `consulta-publica`, `web-publica`)

- [ ] Confirma que `/api/publica/fichas` y `/api/publica/fichas/{id}` no cambian: de ahí depende la URL externa.
- [ ] Confirma si 120 por minuto por IP alcanza. Un campus entero puede compartir IP.
- [ ] Cambia a `denyAll` en `servicio-consulta-publica` y, con Hernando, en `servicio-cientifico`.
- [ ] `web-publica` debe mostrar el aviso de 429 usando `Retry-After`: el gateway ya lo envía y lo expone por CORS.
- [ ] Cierra SEC-J-04: `MapaHallazgos.jsx` debe pedir las teselas al gateway y no a `tile.openstreetmap.org`. El cambio y el código están en §7.

### Plantilla de `SecurityConfig` para un servicio

```java
.authorizeHttpRequests(auth -> auth
        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
        .requestMatchers("/api/equipos/**").hasAnyRole("investigador_campo", "auditor_cientifico",
                "cientifico_alto_rango", "analista_ambiental", "administrador")
        .anyRequest().denyAll())
```

```java
@PreAuthorize("hasRole('administrador')")
@PostMapping("/api/equipos/activos")
public ActivoResponse registrar(...) { ... }
```

## 4. Usuarios RabbitMQ por servicio (SEC-04)

La tarea `rabbitmq-usuarios` del compose puede crear `cientifico_mq`, `consulta_mq` y `reportes_mq`. Es opcional hasta que cada servicio migre, y sirve para cerrar SEC-04 y SEC-D-03. Para migrar un servicio, sin tocar su código:

1. En el `.env` de la raíz, pon contraseñas en `RABBITMQ_<SERVICIO>_PASSWORD` (`CIENTIFICO`, `CONSULTA`, `REPORTES`).
2. Corre `docker compose up -d rabbitmq-usuarios`.
3. En el `.env` de la **carpeta del servicio**, pon `RABBITMQ_USER=<usuario>` y `RABBITMQ_PASSWORD=<su contraseña>`.
4. En `servicio-consulta-publica/.env`, agrega `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO=cientifico_mq`.

## 5. Reglas para agentes y personas

- No cambies las URL de `/api/v1/externa/**`: son un contrato con terceros (ASR9).
- No agregues un endpoint nuevo sin decidir su rol y sin prueba de 403.
- No guardes la API Key en claro ni la escribas en logs. Usa su `prefijo`.
- No uses `X-Forwarded-For` para identificar clientes: el cliente puede inventarlo.
- No apuntes pruebas a Aiven ni a un broker compartido (D30).
- Nunca edites una migración ya aplicada. Las nuevas siguen `VAAAAMMDD_HHMM__descripcion.sql` y deben pasar `deploy/aiven/verificar-migraciones.sh`.
- Avisa antes de tocar zonas compartidas: `pom.xml` raíz, `docker-compose.yml`, `infra/` y el realm.
- No leas, muestres ni subas `.env`, `certs/`, `*.pem` ni `*.p12`.

## 6. Pendiente de la parte de acceso

Las pantallas de `web-interna` van al final, cuando existan los endpoints de negocio: router y menú por rol, montaje de `src/ubicacion/`, y pantallas de API Keys y usuarios. El backend de esas pantallas ya está listo. Contrato: `04-contrato-api.md`.

Sin hacer, a propósito:

- **MFA para `cientifico_alto_rango` (SEC-J-09).** El SRS no lo pide, y obligaría a configurar OTP a ese usuario de prueba.
- **Compose de los servicios en contenedores (SEC-J-I19, SEC-J-18, ARQ-24).** Hoy cada integrante corre los servicios en su equipo (D34). Si algún día se despliega en contenedores, ese compose debe cumplir tres cosas:
  - Ningún puerto de servicio publicado en el host.
  - Una red `internal` compartida solo con el gateway.
  - Un volumen para `/app/logs` y `cpus`/`mem_limit` por servicio.

## 7. Teselas del mapa por el gateway (SEC-J-04)

**Problema:** el mapa de `web-interna` pedía las teselas directamente a `tile.openstreetmap.org`. Así, OpenStreetMap veía tres cosas juntas: la IP del científico, la hora y las teselas alrededor de un hallazgo protegido. Con eso puede deducir el área del hallazgo.

**Solución:** el navegador pide la tesela al gateway, y el gateway la pide a OSM. OSM solo ve al gateway. La caché es compartida, así que una tesela que ya pidió otro usuario ni siquiera sale a internet.

| Regla | Valor | Por qué |
|---|---|---|
| Quién | JWT con uno de los 5 roles | Si fuera abierto, cualquiera usaría el gateway como relé hacia OSM |
| Qué | Solo teselas que existen, con zoom de 0 a 12 | El mismo tope `ZOOM_MAXIMO` del mapa. Fuera de rango: 400 `PARAMETRO_INVALIDO` |
| Cuánto | 600 teselas por minuto por usuario | Una vista usa entre 20 y 40. Con ese tope nadie descarga el mapa entero. Si se pasa: 429 `LIMITE_EXCEDIDO` con `Retry-After` |
| Hacia OSM | 2 descargas a la vez y `User-Agent` de BioHub, sin Referer, IP, token ni cookies | Lo pide la política de uso de OSM |
| Caché | En memoria, 64 MB y 7 días; el navegador guarda 1 día (`private`) solo las de zoom 0 a 8; desde el zoom 9 el front las pide con `cache: 'no-store'` y no quedan en disco (SEC-J-24) | No va en Redis: con `volatile-lru`, las teselas podrían expulsar las marcas de revocación |
| Fallos | Circuit breaker (D52): 503 `SERVICIO_NO_DISPONIBLE`. Respuesta que no es imagen: 502 `TESELA_NO_DISPONIBLE` | Un OSM caído no ocupa hilos del gateway |
| Log | Nunca registra z/x/y | Esas coordenadas delatarían el área del hallazgo |

**Cambio en el front: hecho.** Leaflet carga las teselas con `<img>`, y un `<img>` no puede enviar `Authorization`. Por eso `web-interna/src/ubicacion/componentes/CapaTeselasGateway.js` es una capa Leaflet propia que las pide con `fetch` y las muestra como blob. Así quedó, con dos diferencias respecto del código que se sugería antes:

- **Token y host por el cliente inyectado (D17).** La capa no sabe de Keycloak ni del host: llama a `cliente.getBlob('/api/gateway/teselas/{z}/{x}/{y}.png', { signal, sinCache })`. `crearClienteUbicacion` antepone `apiUrl`, envía `Authorization: Bearer`, `Accept: image/png` y `credentials: 'omit'`, y con `sinCache: true` (zoom ≥ 9, D57) usa `cache: 'no-store'`; las de zoom 0 a 8 se piden sin él para que el navegador las reutilice (`private, max-age=86400`). Santiago no cambia nada. No se usa `@react-leaflet/core`: `TeselasGateway.jsx` añade la capa con `useMap`, así que no hay dependencia nueva.
- **Abortos y memoria.** Cada tesela tiene su `AbortController`, que se aborta cuando Leaflet la descarta (`tileunload`, `tileabort`, cambio de zoom, quitar el mapa), para no gastar el límite de 600/min. El object URL se revoca siempre (al cargar, al fallar o al descartarse). Un aborto no cuenta como error; cualquier otro fallo dispara `tileerror` y alimenta el aviso "No se pudo cargar el mapa base". Un 401 de tesela llama una sola vez a `alSesionVencida`.

Las pruebas (`src/ubicacion/pruebas/`) comprueban la URL del gateway con `Bearer`, que no se pide nada a OSM, el aborto, la revocación y el 401. Sigue pendiente verlo en un navegador real con el gateway levantado.

La atribución a OpenStreetMap se mantiene: la licencia la exige aunque la tesela llegue por el gateway. Si se agrega una CSP a `web-interna` (SEC-J-07), debe incluir `img-src 'self' data: blob:` y `connect-src` hacia el gateway.

**Configuración** (`application.yml`, `biohub.gateway.teselas`): `GATEWAY_TESELAS_URL` para usar otro servidor de teselas, `GATEWAY_TESELAS_AGENTE`, `GATEWAY_TESELAS_POR_USUARIO` y `GATEWAY_TESELAS_CACHE`. Código: `TeselasController`, `ServicioTeselas`, `OrigenTeselasHttp`.
