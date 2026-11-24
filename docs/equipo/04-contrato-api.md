# 04 – Contrato de API

Base: `http://localhost:8080` (API Gateway). Autenticación: `Authorization: Bearer <JWT de Keycloak>`.

## Enrutamiento del gateway
| Prefijo | Servicio destino | ¿JWT? |
|---|---|---|
| `/api/gateway/**` | el propio gateway | Sí |
| `/api/cientifico/**` | servicio-cientifico (8081) | Sí |
| `/api/equipos/**` | servicio-equipos (8082) | Sí |
| `/api/publica/**` | servicio-consulta-publica (8083) | No (solo GET). Límite por IP |
| `/api/reportes/**` | servicio-reportes (8084) | Sí |
| `/api/v1/externa/fichas/**` | servicio-consulta-publica, reescrita a `/api/publica/fichas/**` | No: exige `X-API-Key` (solo GET) |

La ruta se reenvía completa (`/api/cientifico/x` → `http://localhost:8081/api/cientifico/x`), salvo la API externa. Roles por capacidad: `11-seguridad-y-acceso.md` §2.

## GET /api/{gateway|cientifico|equipos|publica|reportes}/ping
Prueba de punta a punta.

**200**
```json
{ "servicio": "servicio-cientifico", "usuario": "investigador1",
  "roles": ["investigador_campo"], "fecha": "2026-10-21T23:14:39.749Z" }
```
- `roles`: solo roles de negocio (sin `offline_access`, `uma_authorization`, `default-roles-*`).
- En `/api/publica/ping` sin token: `"usuario": "anonimo", "roles": []`.

## Errores
| Código | Cuándo |
|---|---|
| 401 | Falta el token, o es inválido, vencido, de otro emisor o sin `aud=biohub-api` |
| 403 | CORS desde un origen no permitido |
| 404 | Prefijo sin servicio asociado |
| 400 | Cuerpo inválido o con validación fallida (`application/problem+json`) |
| 403 | Rol insuficiente (JWT válido sin el rol requerido) |
| 503 | RabbitMQ no disponible o sin confirmar el evento (`application/problem+json`, sin detalles internos) |
| 502 | Servicio destino caído (`application/problem+json`, sin detalles internos) |

## Eventos (RabbitMQ)

Mensajería asíncrona entre servicios. Los endpoints siguientes verifican el flujo de punta a punta con un evento de prueba; sirven de base para el evento real `hallazgo.registrado`.

### Topología
| Elemento | Nombre | Declara |
|---|---|---|
| Exchange topic durable | `biohub.eventos` | cientifico (publicador) y los consumidores |
| Dead-letter exchange topic durable | `biohub.eventos.dlx` | consumidores |
| Cola durable (`x-dead-letter-exchange=biohub.eventos.dlx`, `x-dead-letter-routing-key=<cola>.dlq`) | `consulta-publica.eventos` / `reportes.eventos` | consulta-publica / reportes |
| DLQ durable | `consulta-publica.eventos.dlq` / `reportes.eventos.dlq` | consulta-publica / reportes |
| Bindings cola → `biohub.eventos` | `prueba.#`, `hallazgo.#` | cada consumidor |
| Binding DLQ → `biohub.eventos.dlx` | clave = nombre de la DLQ | cada consumidor |

### Sobre del evento
Mensaje JSON (`application/json`), persistente (`delivery_mode=2`), `message_id` = `id`. Routing key = `tipo`. No se envía la cabecera `__TypeId__`.
```json
{ "id": "89dae52d-f25d-4282-bcfa-adcc26a3b423", "tipo": "prueba.ping", "origen": "servicio-cientifico",
  "fecha": "2026-10-22T04:33:23.726914555Z", "usuario": "admin1", "datos": { "mensaje": "hola" } }
```
- `usuario`: `preferred_username` del JWT que originó el evento. `fecha`: ISO-8601 UTC.
- Tipos conocidos por los consumidores: `prueba.ping` (`datos.mensaje`: string, máx 200) y `hallazgo.registrado` (datos libres hasta definir el modelo). Para agregar un tipo: añadirlo a `TIPOS_CONOCIDOS` en `ConsumidorEventos` de cada consumidor.
- Un mensaje con JSON roto, sobre incompleto, tipo desconocido, routing key distinta del `tipo` o de más de 64 KB se rechaza sin reencolar y va a la DLQ. **Excepción:** si el cuerpo trae coordenadas (§7.3, §7.5) se descarta con ack y se registra un incidente, sin pasar por la DLQ. Otros fallos del consumidor se reintentan 3 veces (1 s, 2 s) y luego van a la DLQ.

### POST /api/cientifico/eventos/prueba
Publica un evento `prueba.ping`. Requiere JWT con rol `administrador`.

Cuerpo opcional: `{ "mensaje": "texto" }` (máx 200 caracteres; por defecto `"ping"`).

**202** – el sobre publicado (ver arriba), tras la confirmación del broker (publisher confirm).
**400** – mensaje de más de 200 caracteres o JSON inválido. **401** sin token. **403** sin rol `administrador`. **503** RabbitMQ no disponible.

### GET /api/publica/eventos/recibidos  ·  GET /api/reportes/eventos/recibidos
Últimos 20 eventos recibidos por servicio-consulta-publica / servicio-reportes (en memoria, del más reciente al más antiguo). Requiere JWT con rol `administrador`, validado en el propio servicio (aunque `/api/publica/**` GET sea público en el gateway, que reenvía `Authorization`).

**200**
```json
{ "servicio": "servicio-reportes", "total": 1,
  "eventos": [ { "id": "89dae52d-...", "tipo": "prueba.ping", "origen": "servicio-cientifico",
                 "fecha": "2026-10-22T04:33:23.726914555Z", "usuario": "admin1", "datos": { "mensaje": "hola" } } ] }
```
**401** sin token (también en `/api/publica/eventos/**`). **403** sin rol `administrador`.

---

# Parte de Juanita: ubicación, consulta pública y eventos `hallazgo.*`

Fecha: 2026-10-26. Diseño: `03-arquitectura.md` (D14–D31, sección "Parte de Juanita"). Modelo físico: `05-modelo-datos.md`. Pantallas: `02-diseno-ux.md`.
**Esta sección es la fuente de verdad** de los nombres de campo, parámetros y códigos de estos recursos. Para los eventos `hallazgo.*`, sustituye a la nota "datos libres hasta definir el modelo" de la sección "Eventos (RabbitMQ)" de arriba.

## 1. Convenciones

| Tema | Regla |
|---|---|
| Rutas | El gateway reenvía la ruta completa: `GET {gateway}/api/publica/fichas` → `http://<consulta-publica>:8083/api/publica/fichas`; `GET {gateway}/api/cientifico/ubicacion/...` → `http://<cientifico>:8081/api/cientifico/ubicacion/...`. Local: `{gateway}` = `http://localhost:8080`; VMs: `http://biohub-backend:8080` |
| JSON | `camelCase`. Fechas `YYYY-MM-DD`. Instantes ISO-8601 UTC. UUID en minúsculas. Enumeraciones en `MAYUSCULAS_CON_GUION_BAJO` |
| Campos opcionales | Se envían como `null`, **salvo `latitud` y `longitud`**, que **no aparecen** en el JSON cuando el usuario no puede verlas o no existen |
| Parámetros de consulta | Se recortan los espacios; el texto vacío equivale a ausente; los desconocidos se ignoran (por ejemplo `?incluirCoordenadas=true` no cambia nada). Excepción: `bbox` (ver §5.1) |
| Paginación | `page` **base 0** (por defecto 0), `size` (por defecto 20). Respuesta `{ content, page, size, total, totalPaginas }`. Límite: `(page + 1) × size ≤ 10.000` |
| Orden | Fijo, sin parámetro. Público: `nombreCientifico` ascendente. Interno: `fechaRecoleccion` descendente (desempate por id) |
| Búsqueda de texto | Sin distinguir mayúsculas ni tildes. Público `q`: por palabras (prefijo) en nombre científico y común. Interno `especie`: el nombre científico empieza por el texto |
| Departamento (filtro) | Código DANE de 2 dígitos (`"15"`) **o** nombre oficial (`"Boyacá"`, `"boyaca"`). Catálogo cerrado de 33 (lista en `05-modelo-datos.md` §3.1). Uno desconocido → 400. web-publica y web-interna envían el **código** |
| Errores | `application/problem+json` (formato §2). El 401 no lleva cuerpo |
| Coordenadas | **Nunca** en `/api/publica/**`. En `/api/cientifico/ubicacion/**` solo para `cientifico_alto_rango` |

### Enumeraciones
| Nombre | Valores | Texto sugerido para la UI |
|---|---|---|
| `estadoConservacion` | `EN_PELIGRO`, `NO_AMENAZADA`, `SIN_EVALUAR` | "En peligro", "No amenazada", "Sin evaluar" |
| `estadoCoordenadas` (solo interno) | `VISIBLES` (vienen `latitud`/`longitud`), `PROTEGIDAS` (las hay, pero tu rol no puede verlas), `SIN_COORDENADAS` (el hallazgo no tiene), `NO_DISPONIBLES` (las hay, pero no se pudieron descifrar; solo para alto rango) | "—" / "Restringido" / "Sin coordenadas" / "No disponible" |

## 2. Formato de error estándar

```json
{
  "type": "about:blank",
  "title": "Parámetros inválidos",
  "status": 400,
  "detail": "Revisa los parámetros de la consulta.",
  "instance": "/api/publica/fichas",
  "codigo": "PARAMETRO_INVALIDO",
  "errores": [ { "campo": "size", "mensaje": "debe estar entre 1 y 50" } ]
}
```
- `errores` solo aparece en los 400 de validación. `detail` está en español y nunca lleva trazas, SQL, coordenadas ni datos internos.
- La UI decide por `status` + `codigo`; el texto que se muestra sale de su tabla de microcopy (`02-diseno-ux.md` §11).

| `codigo` | HTTP | Quién lo emite | Cuándo |
|---|---|---|---|
| `PARAMETRO_INVALIDO` | 400 | servicio | Parámetro mal formado o fuera de rango, `id` que no es UUID, departamento desconocido, fechas incoherentes, paginación demasiado profunda |
| `NO_SOPORTADO` | 400 | cientifico | `bbox` enviado por `cientifico_alto_rango` (aún no implementado) |
| – (sin cuerpo, `WWW-Authenticate: Bearer`) | 401 | gateway o servicio | Falta el JWT o es inválido, vencido, de otro emisor o sin `aud=biohub-api`. En `/api/publica/**` solo ocurre si se envía un `Authorization` inválido (web-publica nunca lo envía) |
| `ROL_INSUFICIENTE` | 403 | cientifico | JWT válido sin el rol requerido. Sin datos en el cuerpo |
| `NO_ENCONTRADO` | 404 | servicio | El recurso no existe |
| `CONFLICTO` | 409 | cientifico | Ya hay un re-cifrado en curso |
| `LIMITE_EXCEDIDO` | 429 | **gateway (Santiago)** | Límite por IP, por consumidor o de keys inválidas superado. Lleva la cabecera `Retry-After: <segundos>` |
| `API_KEY_INVALIDA` | 401 | **gateway (Santiago)** | API Key ausente, desconocida, revocada o vencida. Lleva `WWW-Authenticate: ApiKey` y `Cache-Control: no-store`. El mensaje es el mismo en todos los casos |
| `SERVICIO_NO_DISPONIBLE` | 503 | servicio | BD no disponible (consulta-publica: mysql-lectura caída y dato fuera de caché) |
| `ERROR_INTERNO` | 500 | servicio | Error no previsto (sin detalles) |
| – | 502 | gateway | Servicio destino caído (ya existe) |
| `SERVICIO_NO_DISPONIBLE` | 503 | **gateway (Santiago)** | El servicio destino falló varias veces seguidas y su circuito está abierto (D52). Lleva `Retry-After` |

## 3. Cabeceras de caché
| Respuesta | `Cache-Control` |
|---|---|
| `GET /api/publica/fichas/{id}` 200 | `public, max-age=60` |
| `GET /api/publica/fichas` 200 | `public, max-age=30` |
| Cualquier error de `/api/publica/**` | `no-store` |
| Todo `/api/cientifico/ubicacion/**` (éxito y error) | `no-store` |

## 4. Endpoints públicos (servicio-consulta-publica)

Autenticación: **ninguna** (sin JWT ni API Key, D16). Si llega un JWT válido se ignora: la respuesta es idéntica para todos.

### 4.1 `GET /api/publica/fichas` — buscar fichas (HU-06.2)
Ruta en el gateway: `GET {gateway}/api/publica/fichas`. Ruta interna: `GET http://<consulta-publica>:8083/api/publica/fichas`.

| Parámetro | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `q` | texto | No | 2–100 caracteres. Busca en nombre científico y nombre común |
| `familia` | texto | No | 1–100 caracteres; vacío o solo espacios = ausente (§1); coincidencia exacta sin distinguir mayúsculas ni tildes |
| `departamento` | texto | No | Código DANE o nombre oficial (§1) |
| `page` | entero | No | ≥ 0; por defecto 0 |
| `size` | entero | No | 1–50; por defecto 20 |

Ejemplo: `GET /api/publica/fichas?q=atelopus&departamento=15&page=0&size=20`

**200**
```json
{
  "content": [
    {
      "id": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b",
      "nombreCientifico": "Atelopus ebenoides",
      "nombreComun": "Rana arlequín de Boyacá",
      "familia": "Bufonidae",
      "estadoConservacion": "EN_PELIGRO",
      "departamento": "Boyacá",
      "departamentoCodigo": "15",
      "region": "Andina"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1,
  "totalPaginas": 1,
  "modoDegradado": false,
  "filtrosIgnorados": []
}
```
- Sin resultados: `200` con `"content": []`, `"total": 0`, `"totalPaginas": 0`.
- Modo degradado (RNF1, ASR1, ARQ-06): `"modoDegradado": true` y `"filtrosIgnorados": ["familia", "departamento"]` (solo los que se enviaron); `q` y la paginación se aplican igual. **Es la única indicación**: no hay cabecera ni campo nuevos, y el estado HTTP sigue siendo 200 (sin 5xx). Qué lo activa: (1) **automático**, en cada petición, cuando el monitor de carga del servicio detecta saturación (muchas lecturas en curso o p95 alto en una ventana deslizante; vuelve a la búsqueda completa con histéresis para no oscilar), y (2) **forzado manual** con `CONSULTA_MODO_DEGRADADO=true`. Una respuesta degradada puede quedar en las cachés (Redis y HTTP, `Cache-Control: public, max-age=30`) hasta 60 s: el cliente no debe asumir que `modoDegradado` es permanente. Los filtros se validan igual aunque se ignoren (un `departamento` desconocido sigue siendo 400). Estado operativo para operación: `GET /actuator/info` (clave `busqueda`) y las métricas `biohub.consulta.degradado`, `biohub.consulta.en_curso` y `biohub.consulta.p95.ms`; no es parte de la API pública.

**400** (ejemplo)
```json
{ "type": "about:blank", "title": "Parámetros inválidos", "status": 400,
  "detail": "Revisa los parámetros de la consulta.", "instance": "/api/publica/fichas",
  "codigo": "PARAMETRO_INVALIDO",
  "errores": [ { "campo": "q", "mensaje": "debe tener entre 2 y 100 caracteres" } ] }
```
Mensajes por campo: `q` "debe tener entre 2 y 100 caracteres"; `familia` "máximo 100 caracteres"; `departamento` "departamento desconocido"; `page` "debe ser un entero mayor o igual a 0"; `size` "debe estar entre 1 y 50"; paginación profunda → campo `page`, "no se puede paginar más allá de 10.000 resultados".

**429** (gateway)
```json
{ "type": "about:blank", "title": "Demasiadas peticiones", "status": 429,
  "detail": "Superaste el límite de peticiones. Intenta de nuevo en unos segundos.",
  "instance": "/api/publica/fichas", "codigo": "LIMITE_EXCEDIDO" }
```
**503**
```json
{ "type": "about:blank", "title": "Servicio no disponible", "status": 503,
  "detail": "El servicio no está disponible en este momento. Intenta de nuevo.",
  "instance": "/api/publica/fichas", "codigo": "SERVICIO_NO_DISPONIBLE" }
```
No aplican: 401 (salvo `Authorization` inválido), 403 ni 404.

### 4.2 `GET /api/publica/fichas/{id}` — ficha de especie (HU-06.3)
Ruta en el gateway: `GET {gateway}/api/publica/fichas/{id}`. Ruta interna: `GET http://<consulta-publica>:8083/api/publica/fichas/{id}`.
`id` = `hallazgoId` (UUID).

**200**
```json
{
  "id": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b",
  "nombreCientifico": "Atelopus ebenoides",
  "nombreComun": "Rana arlequín de Boyacá",
  "familia": "Bufonidae",
  "tipoEspecimen": "Adulto",
  "fechaRecoleccion": "2026-03-14",
  "estadoConservacion": "EN_PELIGRO",
  "departamento": "Boyacá",
  "departamentoCodigo": "15",
  "region": "Andina",
  "descripcion": "Anfibio endémico de páramo…",
  "ubicacionProtegida": true
}
```
- `ubicacionProtegida` es **siempre** `true` (D14). El cuerpo **nunca** contiene `latitud`, `longitud`, `coordenadas` ni datos del investigador.
- `nombreComun`, `tipoEspecimen` y `descripcion` pueden ser `null`.

**400**: `id` que no es UUID → `codigo: PARAMETRO_INVALIDO`, `errores: [{ "campo": "id", "mensaje": "identificador inválido" }]`.
**404**
```json
{ "type": "about:blank", "title": "No encontrado", "status": 404,
  "detail": "La ficha solicitada no existe.", "instance": "/api/publica/fichas/3f2a9c1e-…",
  "codigo": "NO_ENCONTRADO" }
```
**429** y **503**: como en §4.1 (si la ficha está en caché se sirve aunque mysql-lectura esté caída).

### 4.4 Catálogo por especie, familias y foto pública (D65)
Sin autenticación, como §4.1. Nunca traen coordenadas ni el investigador.

| Método y ruta | Respuesta |
|---|---|
| `GET /api/publica/especies?q=&familia=&departamento=&page=&size=` | Una fila por `nombreCientifico`, con los mismos filtros, validaciones y modo degradado de §4.1 (`size` de 1 a 50). `Cache-Control: public, max-age=30` |
| `GET /api/publica/especies/familias` | `{ "familias": ["Asteraceae", "Bufonidae", …] }`: las familias con registros públicos, en orden y sin repetir (tope 500) |
| `GET /api/publica/especies/fotos/{fotoId}` (D66) | **200** `image/jpeg` (a lo sumo 1200 px, sin metadatos), `Cache-Control: public, max-age=31536000, immutable`, `nosniff`, `ETag` = sha256. **404** si no existe o ya no es pública. **400** si `fotoId` no es un UUID. Es la ruta que usa el portal |
| `GET /api/publica/especies/foto?nombre=Ara%20macao` | Se mantiene por compatibilidad: la foto pública más reciente de la especie, servida desde la base de lectura. `Cache-Control: public, max-age=300`. **404** si no hay. **400** si `nombre` no es un nombre científico (letras, espacios, `.`, `'` y `-`, de 2 a 150) |

Elemento del catálogo:
```json
{ "nombreCientifico": "Atelopus ebenoides", "nombreComun": "Rana arlequín de Boyacá", "familia": "Bufonidae",
  "estadoConservacion": "EN_PELIGRO", "registros": 2, "departamentos": ["Boyacá", "Cundinamarca"],
  "regiones": ["Andina"], "ultimaRecoleccion": "2026-05-02", "fichaId": "3f2a9c1e-…", "fotoId": "9b1d…" }
```
`nombreComun`, `familia`, `estadoConservacion` y `fichaId` son los del registro más reciente; `fichaId` se abre con §4.2. `fotoId` es la foto pública más reciente de la especie, o `null` si no tiene: el portal solo pide `/especies/fotos/{fotoId}` cuando no es `null`. En modo degradado el catálogo puede devolver `fotoId: null` y `familias` vacías.

**Fotos (D66):** consulta-publica no llama al Servicio Científico. Las miniaturas le llegan por el evento `evidencia.publicada` (§7.6) y las sirve desde `foto_publica` en `mysql-lectura`.

## 5. Endpoints internos (servicio-cientifico, módulo ubicacion)

Autenticación: `Authorization: Bearer <JWT>` obligatorio (validado en el gateway y en el servicio). Respuestas siempre con `Cache-Control: no-store`.

### 5.1 `GET /api/cientifico/ubicacion/hallazgos` — búsqueda interna (HU-13)
Ruta en el gateway: `GET {gateway}/api/cientifico/ubicacion/hallazgos`. Ruta interna: `GET http://<cientifico>:8081/api/cientifico/ubicacion/hallazgos`.
**Roles:** `investigador_campo`, `auditor_cientifico`, `cientifico_alto_rango`, `analista_ambiental`, `administrador`. **Solo `cientifico_alto_rango` recibe `latitud`/`longitud`**.

| Parámetro | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `especie` | texto | No | 2–100 caracteres; el nombre científico empieza por el texto |
| `desde` | fecha `YYYY-MM-DD` | No | Fecha válida |
| `hasta` | fecha `YYYY-MM-DD` | No | Fecha válida; `desde ≤ hasta` |
| `investigador` | texto | No | 1–100 caracteres; igual al `preferred_username` del investigador |
| `departamento` | texto | No | Código DANE o nombre oficial |
| `page` | entero | No | ≥ 0; por defecto 0 |
| `size` | entero | No | 1–100; por defecto 20 |
| `bbox` | texto | No | **Reservado (Could).** Si lo envía un rol sin permiso → **403**; si lo envía `cientifico_alto_rango` → 400 `NO_SOPORTADO` |

Ejemplo: `GET /api/cientifico/ubicacion/hallazgos?especie=Atelopus&desde=2026-01-01&hasta=2026-06-30&page=0&size=20`

**200 para `cientifico_alto_rango`**
```json
{
  "content": [
    {
      "id": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b",
      "nombreCientifico": "Atelopus ebenoides",
      "fechaRecoleccion": "2026-03-14",
      "investigador": "investigador1",
      "departamento": "Boyacá",
      "departamentoCodigo": "15",
      "region": "Andina",
      "estadoConservacion": "EN_PELIGRO",
      "estadoCoordenadas": "VISIBLES",
      "ubicacionProtegida": false,
      "latitud": 5.1234567,
      "longitud": -73.1234567
    },
    {
      "id": "8b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
      "nombreCientifico": "Atelopus muisca",
      "fechaRecoleccion": "2026-02-02",
      "investigador": "investigador2",
      "departamento": "Cundinamarca",
      "departamentoCodigo": "25",
      "region": "Andina",
      "estadoConservacion": "EN_PELIGRO",
      "estadoCoordenadas": "NO_DISPONIBLES",
      "ubicacionProtegida": false
    }
  ],
  "page": 0, "size": 20, "total": 2, "totalPaginas": 1,
  "omitidos": 1
}
```
**200 para cualquier otro rol** (mismo hallazgo): sin `latitud`/`longitud`, con `"estadoCoordenadas": "PROTEGIDAS"` y `"ubicacionProtegida": true` (o `SIN_COORDENADAS`/`false` si no tiene). `omitidos` siempre vale 0.
```json
{ "id": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b", "nombreCientifico": "Atelopus ebenoides",
  "fechaRecoleccion": "2026-03-14", "investigador": "investigador1", "departamento": "Boyacá",
  "departamentoCodigo": "15", "region": "Andina", "estadoConservacion": "EN_PELIGRO",
  "estadoCoordenadas": "PROTEGIDAS", "ubicacionProtegida": true }
```
- `ubicacionProtegida` = `true` si y solo si `estadoCoordenadas = PROTEGIDAS`.
- `omitidos` = filas de la página con `NO_DISPONIBLES` (ver `02` §3.4).
- Sin resultados: `200` con `content: []`, `total: 0`.

**400** (`PARAMETRO_INVALIDO`): `desde` posterior a `hasta` (campo `hasta`, "debe ser igual o posterior a 'desde'"), fecha mal formada ("fecha inválida, formato AAAA-MM-DD"), `size` > 100, `especie` > 100, departamento desconocido, paginación profunda. **400** (`NO_SOPORTADO`): `bbox` enviado por alto rango.
**401**: sin token o token inválido (sin cuerpo).
**403**
```json
{ "type": "about:blank", "title": "Acceso denegado", "status": 403,
  "detail": "No tienes permiso para ver esta información.",
  "instance": "/api/cientifico/ubicacion/hallazgos", "codigo": "ROL_INSUFICIENTE" }
```
(usuario sin ninguno de los 5 roles, o `bbox` enviado por un rol sin permiso).
**503**: BD no disponible (`SERVICIO_NO_DISPONIBLE`).

### 5.2 `GET /api/cientifico/ubicacion/mapa` — mapa con coordenadas exactas (HU-07)
Ruta en el gateway: `GET {gateway}/api/cientifico/ubicacion/mapa`. Ruta interna: `GET http://<cientifico>:8081/api/cientifico/ubicacion/mapa`.
**Rol:** solo `cientifico_alto_rango`. Cada acceso, permitido o denegado, queda en la auditoría (D28).

| Parámetro | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `especie` | texto | No | 2–100 caracteres (prefijo del nombre científico) |
| `departamento` | texto | No | Código DANE o nombre oficial |
| `desde`, `hasta` | fecha `YYYY-MM-DD` | No | `desde ≤ hasta` |
| `hallazgoId` | UUID | No | Si viene, se ignoran los demás filtros y la respuesta trae 0 o 1 feature (para "Ver en el mapa", `02` §2 F7) |

Solo se incluyen hallazgos **con coordenadas**. Como máximo 1.000 features, ordenadas por `fechaRecoleccion` descendente.

**200** (GeoJSON `FeatureCollection` + miembros propios)
```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "id": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b",
      "geometry": { "type": "Point", "coordinates": [-73.1234567, 5.1234567] },
      "properties": {
        "hallazgoId": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b",
        "nombreCientifico": "Atelopus ebenoides",
        "fechaRecoleccion": "2026-03-14",
        "investigador": "investigador1",
        "departamento": "Boyacá",
        "departamentoCodigo": "15",
        "region": "Andina",
        "estadoConservacion": "EN_PELIGRO"
      }
    }
  ],
  "total": 1,
  "devueltos": 1,
  "truncado": false,
  "omitidos": 0,
  "limite": 1000
}
```
- **`coordinates` = `[longitud, latitud]`** (orden GeoJSON). Leaflet usa `[latitud, longitud]`: convertir en un solo lugar del frontend.
- `total` = hallazgos con coordenadas que cumplen los filtros. `devueltos` = features enviadas. `truncado` = `total > limite`. `omitidos` = registros que no se pudieron descifrar (no están en `features`).
- Sin resultados: `200` con `"features": []`, `"total": 0`, `"devueltos": 0`, `"truncado": false`.

**400**: igual que §5.1 (fechas, `especie`, departamento, `hallazgoId` mal formado: campo `hallazgoId`, "identificador inválido").
**401**: sin token o token inválido (sin cuerpo).
**403**: cualquier rol que no sea `cientifico_alto_rango` (cuerpo como en §5.1, sin coordenadas).
**503**: BD no disponible.

### 5.3 `POST /api/cientifico/ubicacion/recifrado` — re-cifrado tras rotar la llave (HU-UBI-02, Should)
Ruta en el gateway: `POST {gateway}/api/cientifico/ubicacion/recifrado`. Ruta interna: `POST http://<cientifico>:8081/api/cientifico/ubicacion/recifrado`.
**Rol:** `administrador`. Sin cuerpo. Síncrono: procesa todas las filas que no están en la llave activa, en lotes de 500. Nunca devuelve coordenadas.

**200**
```json
{ "claveActiva": "v2", "revisados": 120, "recifrados": 118, "fallidos": 2,
  "pendientesPorVersion": { "v1": 2 } }
```
- `fallidos` = filas que no se pudieron descifrar (llave retirada o dato alterado); siguen en su versión y aparecen en `pendientesPorVersion`. Sus ids van al log del servidor, no a la respuesta.
- Es idempotente: si se repite cuando ya no hay pendientes, responde `revisados: 0` y `pendientesPorVersion: {}`.

**401** sin token. **403** sin rol `administrador`. **409** (`CONFLICTO`): ya hay un re-cifrado en curso. **503** BD no disponible.

## 6. Contrato con registro (Hernando): interfaz `UbicacionService`

Llamada en proceso (mismo `servicio-cientifico`), **no es HTTP**. `registro` solo puede importar estos tipos del paquete `co.edu.unbosque.biohub.cientifico.ubicacion.service`.

```java
package co.edu.unbosque.biohub.cientifico.ubicacion.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * API pública del módulo ubicación. Debe invocarse DENTRO de la transacción de registro
 * (propagation = MANDATORY): si no hay transacción activa lanza IllegalTransactionStateException.
 * Cualquier excepción revierte la transacción completa (hallazgo incluido).
 */
public interface UbicacionService {

    /**
     * Alta de la ubicación de un hallazgo recién registrado. Cifra las coordenadas (AES-256-GCM) y
     * guarda el índice de búsqueda interno.
     * @return APLICADA, o IGNORADA_DUPLICADA si ese hallazgoId ya estaba registrado (no cambia nada)
     * @throws UbicacionInvalidaException si algún dato no cumple las validaciones
     */
    ResultadoUbicacion registrar(RegistroUbicacionComando comando);

    /**
     * Reclasificación o edición de los datos indexados. NO cambia coordenadas ni investigador.
     * @return APLICADA, o IGNORADA_VERSION_ANTIGUA si version <= la guardada (no cambia nada)
     * @throws UbicacionInvalidaException si algún dato no cumple las validaciones
     * @throws UbicacionNoEncontradaException si el hallazgo no se registró antes con registrar(...)
     */
    ResultadoUbicacion actualizar(ActualizacionUbicacionComando comando);
}

/** latitud/longitud: ambas o ninguna (null = hallazgo sin coordenadas). */
public record RegistroUbicacionComando(
        UUID hallazgoId,
        int version,
        String nombreCientifico,
        LocalDate fechaRecoleccion,
        String investigador,                 // preferred_username del JWT de quien registra
        String departamento,                 // código DANE ("15") o nombre oficial ("Boyacá")
        EstadoConservacion estadoConservacion,
        Double latitud,
        Double longitud) {

    @Override
    public String toString() {               // nunca imprime coordenadas
        return "RegistroUbicacionComando[hallazgoId=" + hallazgoId + ", version=" + version + ", coordenadas=***]";
    }
}

public record ActualizacionUbicacionComando(
        UUID hallazgoId,
        int version,
        String nombreCientifico,
        LocalDate fechaRecoleccion,
        String departamento,
        EstadoConservacion estadoConservacion) {
}

public enum EstadoConservacion { EN_PELIGRO, NO_AMENAZADA, SIN_EVALUAR }

public enum ResultadoUbicacion { APLICADA, IGNORADA_DUPLICADA, IGNORADA_VERSION_ANTIGUA }

/** Datos inválidos. El mensaje es seguro para mostrar al usuario (nunca incluye coordenadas). */
public class UbicacionInvalidaException extends RuntimeException {
    public UbicacionInvalidaException(String campo, String mensaje) { super(mensaje); this.campo = campo; }
    private final String campo;
    public String campo() { return campo; }
}

public class UbicacionNoEncontradaException extends RuntimeException {
    public UbicacionNoEncontradaException(UUID hallazgoId) { super("Ubicación no registrada"); this.hallazgoId = hallazgoId; }
    private final UUID hallazgoId;
    public UUID hallazgoId() { return hallazgoId; }
}
```

Validaciones (lanzan `UbicacionInvalidaException(campo, mensaje)`):
| Campo | Regla | Mensaje |
|---|---|---|
| `hallazgoId` | no nulo | "obligatorio" |
| `version` | ≥ 1 | "debe ser mayor o igual a 1" |
| `nombreCientifico` | no vacío, ≤ 150 | "obligatorio, máximo 150 caracteres" |
| `nombreCientifico` | no puede contener coordenadas escritas en texto (p. ej. `5.53214, -73.36712` o `5°31'55"N`): el nombre se muestra a todos los roles y viaja al portal público (SEC-J-02, RNF9) | "no puede contener coordenadas" |
| `fechaRecoleccion` | no nula, no posterior a hoy (zona `America/Bogota`) | "obligatoria y no futura" |
| `investigador` (solo `registrar`) | no vacío, ≤ 100 | "obligatorio, máximo 100 caracteres" |
| `departamento` | existe en el catálogo (código o nombre, sin distinguir mayúsculas ni tildes) | "departamento desconocido" |
| `estadoConservacion` | no nulo | "obligatorio" |
| `latitud`, `longitud` | ambas o ninguna; finitas; latitud en [-90, 90] y longitud en [-180, 180] | "latitud y longitud van juntas" / "latitud fuera de rango" / "longitud fuera de rango" |

Obligaciones de `registro` (Hernando):
1. Insertar su hallazgo **antes** de llamar a `registrar`, en la **misma** transacción (`@Transactional`). Si `registrar` lanza una excepción, la transacción se revierte entera; Hernando responde 400 con `campo` y mensaje.
2. **No guardar las coordenadas** en tablas propias, logs, historial ni eventos: llegan en su request y se pasan tal cual a `registrar`. Los DTOs de entrada que las contengan deben sobrescribir `toString()`.
3. `hallazgoId`: UUID generado por la aplicación y guardado como `BINARY(16)` (`05` §6). `version`: entero que **crece estrictamente** en cada cambio que publique; el mismo valor se usa en `UbicacionService` y en el evento.
4. Reclasificación o edición de `nombreCientifico`/`fechaRecoleccion`/`departamento`/`estadoConservacion` → `actualizar(...)` con la versión nueva, en la misma transacción.
5. Traducir su clasificación al enum de 3 valores. Si usa UICN: `CR`, `EN` → `EN_PELIGRO`; `VU`, `NT`, `LC` → `NO_AMENAZADA`; `DD`, `NE` → `SIN_EVALUAR` (a confirmar por Hernando; cualquier otra equivalencia se documenta aquí).
6. Corregir coordenadas después del alta **no está en el contrato**: si se necesita, se pide a Juanita un método nuevo.

**Forma de invocación recomendada (D40, ARQ-08, P-A3): evento de dominio en lugar de llamada directa.** El SAD (§2.5, §6.4.2, §7.1) dice que registro y ubicacion no se importan entre sí. Por eso `ubicacion.service.ObservadorUbicacion` escucha, con `@EventListener` **síncrono**, dos eventos de `co.edu.unbosque.biohub.cientifico.eventos.domain` y llama a `UbicacionService` por registro:
```java
// registro, dentro de su @Transactional y DESPUÉS de insertar el hallazgo (o la nueva versión):
publicador.publishEvent(new HallazgoRegistrado(hallazgoId, version, nombreCientifico, fechaRecoleccion,
        investigador, departamento, "EN_PELIGRO" /* o NO_AMENAZADA / SIN_EVALUAR */, latitud, longitud, Instant.now()));
publicador.publishEvent(new HallazgoReclasificado(hallazgoId, version, nombreCientifico, fechaRecoleccion,
        departamento, estadoConservacion, Instant.now()));   // no lleva coordenadas
```
- `publicador` es `org.springframework.context.ApplicationEventPublisher`. El listener corre en el mismo hilo y en la misma transacción: equivale a llamar a `registrar`/`actualizar` (mismas validaciones, mismas excepciones, mismo `MANDATORY`). Si lanza una excepción (`UbicacionInvalidaException`, `UbicacionNoEncontradaException` o un `IllegalStateException` de cifrado), `publishEvent` la propaga y se revierte todo el registro, hallazgo incluido; sin transacción activa falla con `IllegalTransactionStateException`.
- `estadoConservacion` viaja como texto (`EN_PELIGRO`, `NO_AMENAZADA`, `SIN_EVALUAR`) para que `eventos.domain` no dependa de ubicacion. Un valor desconocido produce `UbicacionInvalidaException` (campo `estadoConservacion`).
- Los eventos de dominio llevan las coordenadas **solo en memoria** (su `toString()` las enmascara); no se serializan ni van al broker. El `@TransactionalEventListener(AFTER_COMMIT)` que arma el `EventoSobre` público de §7 es de registro y **no debe copiar** `latitud`/`longitud`.
- La llamada directa a `UbicacionService` sigue siendo válida y tiene el mismo contrato; nada de lo anterior cambia las validaciones de la tabla.
- **Nota (D53, ARQ-29, iteración 16): la llamada directa a `UbicacionService` está PROHIBIDA para `registro`.** El texto anterior se conserva solo como referencia del contrato de `UbicacionService`. `registro` no puede importar nada de `co.edu.unbosque.biohub.cientifico.ubicacion`; solo publica `HallazgoRegistrado`/`HallazgoReclasificado` con `ApplicationEventPublisher`. Lo verifica ArchUnit en `ModuloRegistroNoDependeDeUbicacionInternaTest` (falla el build). Además, `PublicadorEventos.publicar` rechaza con `IllegalArgumentException` cualquier `hallazgo.*` con coordenadas (D54, SEC-J-13); `latitud` y `longitud` de `HallazgoRegistrado` llevan `@JsonIgnore`.

## 7. Eventos `hallazgo.*` (los publica registro, los consumen consulta-publica y reportes)

### 7.1 Transporte
| Elemento | Valor |
|---|---|
| Exchange | `biohub.eventos` (topic, durable) |
| Routing key | igual a `tipo`: `hallazgo.registrado`, `hallazgo.reclasificado`, `hallazgo.actualizado` |
| Propiedades AMQP | `content_type=application/json`, `delivery_mode=2`, `message_id` = `id` del sobre, sin `__TypeId__`. `user_id` = usuario AMQP con que el publicador se conecta al broker (`cientifico_mq` por defecto). **Obligatorio**: consulta-publica verifica el `user_id` por defecto (ARQ-13, SEC-J-01); el usuario esperado se cambia con `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO` y la verificación solo se apaga con `BIOHUB_EVENTOS_VERIFICAR_PUBLICADOR=false` en pruebas locales. RabbitMQ rechaza un `user_id` distinto del usuario autenticado, así que no se puede falsificar desde otra conexión. `PublicadorEventos` lo toma de la `ConnectionFactory` (`spring.rabbitmq.username`), no de una constante; en el Compose local es `RABBITMQ_USER` (p. ej. `biohub`) y `BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO` debe valer lo mismo (ARQ-26, SEC-J-11) |
| Publicador | `PublicadorEventos.publicar(tipo, usuario, datos)` (existente), **después del commit** |
| Cuándo | `registrado`: alta del hallazgo (normalmente `version` = 1). `reclasificado`: cambio de `estadoConservacion` o de taxonomía (`nombreCientifico`/`familia`), HU-03. `actualizado`: cualquier otra edición de un campo de `datos` (opcional; si Hernando no edita datos públicos, no lo usa) |

### 7.2 Sobre (sin cambios: `EventoSobre`)
`{ id: UUID, tipo: string, origen: "servicio-cientifico", fecha: ISO-8601 UTC, usuario: preferred_username, datos: objeto }`. Los consumidores **no** guardan `usuario`.

### 7.3 `datos` (idéntico para los tres tipos: **snapshot completo** del hallazgo)
| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `hallazgoId` | string UUID | Sí | Id del hallazgo; clave de la ficha pública |
| `version` | entero | Sí | ≥ 1; crece estrictamente por hallazgo; el mismo valor enviado a `UbicacionService` |
| `nombreCientifico` | string | Sí | 1–150 |
| `nombreComun` | string \| null | No | ≤ 150 |
| `familia` | string | Sí | 1–100 |
| `tipoEspecimen` | string \| null | No | ≤ 80 |
| `fechaRecoleccion` | string `YYYY-MM-DD` | Sí | Fecha válida |
| `estadoConservacion` | string | Sí | `EN_PELIGRO` \| `NO_AMENAZADA` \| `SIN_EVALUAR` (un valor desconocido se trata como `EN_PELIGRO`) |
| `departamento` | string | Sí | Código DANE de 2 dígitos o nombre oficial (catálogo de 33). La región **no** viaja: la deriva el consumidor |
| `descripcion` | string \| null | No | ≤ 5.000; texto público de la ficha |

**Regla de texto: ningún campo de texto de `datos` puede contener coordenadas** (SEC-J-02, RNF9, D14). Vale para `nombreCientifico`, `nombreComun`, `familia`, `tipoEspecimen`, `departamento`, `descripcion` y cualquier otro texto, no solo para las claves con nombre prohibido. El consumidor detecta, en el texto de cualquier valor, pares de grados decimales (`5.53214, -73.36712`), grados con hemisferio (`5.53° N`, `73°22'W`) y grados-minutos-segundos (`5°31'55"N`). No se consideran coordenadas las fechas (`2026-03-14`), las medidas (`12,5 cm`), los rangos de altitud (`3.200, 3.500 msnm`) ni las cantidades. Si un texto legítimo se confunde con una coordenada, el evento se descarta (§7.5) y hay que reescribir el texto. `registro` debe validarlo **antes** de publicar (ubicacion ya lo hace para `nombreCientifico`). JSON Schema no expresa esta regla: se verifica con pruebas.

**Prohibido en `datos` (en cualquier nivel):** `latitud`, `longitud`, `lat`, `lon`, `lng`, `coordenadas`, `coordinates`, `geometry`, `geom`, `ubicacionExacta`, **de cualquier especie** (D14). Tampoco: investigador, justificación científica, historial, rutas de adjuntos ni atributos internos. Los campos extra que no estén prohibidos se ignoran (lector tolerante).

JSON Schema (draft 2020-12) de `datos`, para que Hernando valide en sus pruebas:
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://biohub/eventos/hallazgo-datos.json",
  "type": "object",
  "required": ["hallazgoId", "version", "nombreCientifico", "familia", "fechaRecoleccion", "estadoConservacion", "departamento"],
  "properties": {
    "hallazgoId":         { "type": "string", "format": "uuid" },
    "version":            { "type": "integer", "minimum": 1 },
    "nombreCientifico":   { "type": "string", "minLength": 1, "maxLength": 150 },
    "nombreComun":        { "type": ["string", "null"], "maxLength": 150 },
    "familia":            { "type": "string", "minLength": 1, "maxLength": 100 },
    "tipoEspecimen":      { "type": ["string", "null"], "maxLength": 80 },
    "fechaRecoleccion":   { "type": "string", "format": "date" },
    "estadoConservacion": { "enum": ["EN_PELIGRO", "NO_AMENAZADA", "SIN_EVALUAR"] },
    "departamento":       { "type": "string", "minLength": 1, "maxLength": 60 },
    "descripcion":        { "type": ["string", "null"], "maxLength": 5000 }
  },
  "not": { "anyOf": [
    { "required": ["latitud"] }, { "required": ["longitud"] }, { "required": ["lat"] }, { "required": ["lon"] },
    { "required": ["lng"] }, { "required": ["coordenadas"] }, { "required": ["coordinates"] },
    { "required": ["geometry"] }, { "required": ["geom"] }, { "required": ["ubicacionExacta"] } ] }
}
```
(El consumidor además busca esas claves en objetos anidados y sin distinguir mayúsculas.)

### 7.4 Ejemplos
`hallazgo.registrado`
```json
{
  "id": "6d1f0b7a-2c4e-4f3a-9b8d-1e2f3a4b5c6d",
  "tipo": "hallazgo.registrado",
  "origen": "servicio-cientifico",
  "fecha": "2026-10-26T15:04:05.123Z",
  "usuario": "investigador1",
  "datos": {
    "hallazgoId": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b",
    "version": 1,
    "nombreCientifico": "Atelopus ebenoides",
    "nombreComun": "Rana arlequín de Boyacá",
    "familia": "Bufonidae",
    "tipoEspecimen": "Adulto",
    "fechaRecoleccion": "2026-03-14",
    "estadoConservacion": "NO_AMENAZADA",
    "departamento": "15",
    "descripcion": "Anfibio endémico de páramo…"
  }
}
```
`hallazgo.reclasificado` (mismo hallazgo, ahora en peligro: snapshot completo con `version` mayor)
```json
{
  "id": "a9e8d7c6-b5a4-4392-8170-6f5e4d3c2b1a",
  "tipo": "hallazgo.reclasificado",
  "origen": "servicio-cientifico",
  "fecha": "2026-10-27T09:00:00Z",
  "usuario": "auditor1",
  "datos": {
    "hallazgoId": "3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b",
    "version": 2,
    "nombreCientifico": "Atelopus ebenoides",
    "nombreComun": "Rana arlequín de Boyacá",
    "familia": "Bufonidae",
    "tipoEspecimen": "Adulto",
    "fechaRecoleccion": "2026-03-14",
    "estadoConservacion": "EN_PELIGRO",
    "departamento": "Boyacá",
    "descripcion": "Anfibio endémico de páramo…"
  }
}
```

### 7.5 Comportamiento del consumidor (consulta-publica)
| Caso | Resultado |
|---|---|
| Evento válido con `version` mayor que la guardada, o hallazgo nuevo | INSERT/UPDATE de la ficha; se invalida solo su caché (`fichas::<id>`); búsquedas, catálogo y familias se renuevan por TTL (60 s) (iteración 27); ack |
| Mismo evento repetido, o `version` igual o menor | Se ignora (ack, sin cambios) |
| `reclasificado`/`actualizado` antes que `registrado` | Se crea la ficha con ese snapshot; el `registrado` posterior (versión menor) se ignora |
| `datos` con una clave de coordenadas, o texto con forma de coordenadas en cualquier campo (SEC-J-01/02/03) | **Se descarta con ack: NO va a la DLQ** (la DLQ es un almacén persistente fuera del cifrado y guardaría las coordenadas). No se crea ni cambia ninguna ficha. Se registra un **incidente de seguridad** (log ERROR `INCIDENTE SEC-J-03`) con solo el `id` del evento y el tipo, sin ningún valor. Se analiza el cuerpo de todo mensaje de la cola, también con JSON roto o routing key distinta. El responsable del publicador debe corregirlo y republicar |
| Falta un obligatorio, tipo incorrecto, longitud excedida, departamento desconocido, JSON roto, > 64 KB, routing key ≠ `tipo` (siempre que no traiga coordenadas) | DLQ |
| `origen` ≠ `servicio-cientifico`, o `user_id` ausente o distinto del publicador esperado (verificación activa por defecto, ARQ-13) | DLQ |
| `estadoConservacion` desconocido | Se guarda como `EN_PELIGRO` + log WARN |
| Fallo transitorio (BD caída) | 3 reintentos (1 s, 2 s) y después DLQ; reenviar desde la DLQ es seguro |

Obligaciones del publicador (Hernando):
1. Publicar **después** del commit (`@TransactionalEventListener(phase = AFTER_COMMIT)` o outbox). Recomendado: **outbox transaccional**, porque si la publicación falla tras el commit, el portal queda desactualizado.
2. Un evento por cambio confirmado, con el snapshot completo y la `version` nueva.
3. Probar sus eventos contra el JSON Schema de §7.3 y los ejemplos de §7.4. **Nunca** publicar `hallazgo.*` de prueba en el RabbitMQ de las VMs (crearía fichas públicas reales): usar Testcontainers.

Nicolás (reportes) recibe los mismos eventos por su cola `reportes.eventos` y puede ignorar los campos que no use.

### 7.6 Eventos `evidencia.*`: fotos públicas (D66)
| Elemento | Valor |
|---|---|
| Exchange | `biohub.eventos` (el mismo), mismo sobre (§7.2), mismas propiedades AMQP y verificación de `user_id` que §7.1 |
| Tipos y routing key | `evidencia.publicada`, `evidencia.retirada` |
| Cola | `consulta-publica.fotos` (durable, DLX `biohub.eventos.dlx`, DLQ `consulta-publica.fotos.dlq`), enlazada con `evidencia.#`. `reportes.eventos` **no** la recibe. Límite del mensaje: **1 MB** solo en esta cola (las demás siguen en 64 KB) |
| Cuándo | `publicada`: después del commit de `PUT …/evidencias/{id}/publica` con `publica=true`, al arrancar si faltaba la miniatura de una foto pública, y en la republicación de administrador. `retirada`: después del commit con `publica=false` o si se borra la evidencia o el hallazgo |

`datos` de `evidencia.publicada`:
| Campo | Tipo | Reglas |
|---|---|---|
| `evidenciaId` | UUID | Obligatorio. Es el `fotoId` del portal |
| `hallazgoId` | UUID | Obligatorio. La especie sale de `ficha_publica` con este id |
| `cambioEn` | ISO-8601 UTC | Obligatorio. Instante del cambio de visibilidad; ordena los eventos |
| `sha256` | hex, 64 | Obligatorio. Hash de la miniatura (no del original) |
| `ancho`, `alto` | entero 1..1200 | Obligatorio |
| `jpegBase64` | string Base64 | Obligatorio. JPEG de a lo sumo 600 KB decodificado |

`datos` de `evidencia.retirada`: `evidenciaId`, `hallazgoId`, `cambioEn`.

Comportamiento del consumidor (consulta-publica):
| Caso | Resultado |
|---|---|
| `publicada` con `cambioEn` mayor que el guardado, o foto nueva | UPSERT en `foto_publica` (`publica = true`, bytes, sha256, tamaño, `cambio_en`); se invalida la caché del catálogo y de la foto de esa especie; ack |
| `retirada` con `cambioEn` mayor | `publica = false` y se borran los bytes; se invalida lo mismo; ack |
| `cambioEn` igual o menor (repetido o desordenado) | Se ignora; ack |
| `jpegBase64` que no decodifica, no empieza por `FF D8`, pasa de 600 KB, no coincide con `sha256` o trae segmentos `APP1` (Exif/XMP) o `APP13` | DLQ; no se guarda nada |
| Coordenadas en cualquier campo **salvo** `jpegBase64` (que no es texto) | Descarte con ack e incidente, como §7.5 |
| Sobre inválido, origen o `user_id` incorrecto, más de 1 MB | DLQ, como §7.5 |

## 8. Rutas del gateway para la parte de Juanita (las implementa o confirma Santiago)
| Ruta | Destino | JWT | Acción |
|---|---|---|---|
| `GET /api/publica/fichas`, `GET /api/publica/fichas/{id}` | consulta-publica (8083) | No | **Hecho.** Sin API Key (D16), 120 por minuto por IP, 429 `LIMITE_EXCEDIDO` + `Retry-After` |
| `GET /api/cientifico/ubicacion/hallazgos`, `GET /api/cientifico/ubicacion/mapa`, `POST /api/cientifico/ubicacion/recifrado` | cientifico (8081) | Sí | **Hecho.** El gateway exige uno de los 5 roles; el rol de cada endpoint lo valida el servicio |
| `/api/v1/externa/fichas/**` (API externa, D16) | consulta-publica, **reescribiendo** a `/api/publica/fichas/**` | No (API Key) | **Hecho.** Ver §9 |
| CORS | – | – | `Access-Control-Expose-Headers: Retry-After` **hecho**. **Falta** el origen de web-publica en Vercel cuando exista |
| Cabeceras de respuesta | – | – | **Hecho.** `Cache-Control` pasa sin filtrar, salvo en la API externa, donde `public` pasa a `private` (D47) |
| `GET /api/gateway/teselas/{z}/{x}/{y}.png` (fondo del mapa, SEC-J-04, D56) | El gateway, que la pide a OSM | Sí, uno de los 5 roles | **Hecho.** Ver §8.1. **Hecho en el front:** `MapaHallazgos.jsx` ya lo usa, con `CapaTeselasGateway` y `cliente.getBlob` (`11-seguridad-y-acceso.md` §7) |

### 8.1 Teselas del mapa

- **Entrada:** `z` entre 0 y 12, y `x`, `y` entre 0 y 2^z − 1, en el esquema XYZ de OSM. Solo `GET`, con `Authorization: Bearer <JWT>`.
- **200:** la imagen (`image/png`) con `Cache-Control: max-age=86400, private`.
- **Errores:**

| Estado | Código | Cuándo |
|---|---|---|
| 400 | `PARAMETRO_INVALIDO` | La tesela no existe o el zoom pasa de 12 |
| 401 | – | Sin token |
| 403 | `ROL_INSUFICIENTE` | El usuario no tiene ninguno de los 5 roles |
| 429 | `LIMITE_EXCEDIDO` | Más de 600 teselas en un minuto; trae `Retry-After` |
| 502 | `TESELA_NO_DISPONIBLE` | El origen respondió algo que no es una imagen |
| 502 | – | El origen no responde |
| 503 | `SERVICIO_NO_DISPONIBLE` | Circuito abierto o descargas saturadas; trae `Retry-After` |

- **Privacidad:** el gateway no reenvía nada del navegador al origen, ni IP, ni token, ni cookies, ni Referer. Tampoco escribe z/x/y en el log.

## 9. API externa (HU-08, RF14, RNF11, RNF12)

Para universidades y ONG. Estas URL no cambian aunque los servicios internos cambien de lugar (ASR9).

| Ruta en el gateway | Respuesta |
|---|---|
| `GET /api/v1/externa/fichas` | Igual que §4.1, con los mismos parámetros |
| `GET /api/v1/externa/fichas/{id}` | Igual que §4.2 |

- **Autenticación:** cabecera `X-API-Key: bh_pub_xxxxxxxx_...`. Nunca en la URL.
- **Límite:** 60 peticiones por minuto por consumidor, salvo que tenga uno propio. Dos keys del mismo consumidor comparten el cupo. La ventana es de 60 segundos desde la primera petición.
- **Cabeceras de éxito:** `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Cache-Control: private, max-age=30`.
- **401:** `API_KEY_INVALIDA`. **429:** `LIMITE_EXCEDIDO` + `Retry-After`. Tras 20 keys inválidas en un minuto, la IP también recibe 429. **503:** `SERVICIO_NO_DISPONIBLE` si no se puede validar la key.

## 10. Administración de consumidores y API Keys (HU-12, RNF11)

Requieren JWT con rol `administrador`. Sin token: 401. Otro rol: 403 `ROL_INSUFICIENTE`. Errores: 400 `PARAMETRO_INVALIDO` (con `errores[]`), 404 `NO_ENCONTRADO`, 409 `CONFLICTO`.

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `POST /api/gateway/consumidores` | `{ nombre, contactoEmail, limitePorMinuto? }` | 201 + consumidor. 409 si el nombre existe |
| `GET /api/gateway/consumidores?page&size` | – | 200 + página. `size` entre 1 y 100 |
| `GET /api/gateway/consumidores/{id}` | – | 200 + consumidor |
| `PUT /api/gateway/consumidores/{id}/limite` | `{ limitePorMinuto }` entre 1 y 100.000; `null` vuelve al valor por defecto | 200 + consumidor. Se aplica de inmediato |
| `POST /api/gateway/consumidores/{id}/keys` | `{ expiraEn? }` opcional, ISO-8601 futura | 201 + `{ id, apiKey, prefijo, expiraEn, creadaEn }`, con `no-store`. **`apiKey` solo se muestra aquí** |
| `GET /api/gateway/consumidores/{id}/keys` | – | 200 + `[{ id, prefijo, estado, expiraEn, ultimoUsoEn, creadaEn, revocadaEn }]`. Nunca la key ni su hash |
| `DELETE /api/gateway/keys/{id}` | – | 204. Revoca de inmediato y es idempotente |

Consumidor: `{ id, nombre, contactoEmail, limitePorMinuto, limiteEfectivo, creadoEn }`. `limitePorMinuto` es `null` si usa el valor por defecto; `limiteEfectivo` siempre trae el número aplicado. `ultimoUsoEn` se actualiza cada 5 minutos.

## 11. Administración de usuarios internos (HU-10, RF17, RF19)

Requieren JWT con rol `administrador`. `id` es el `sub` de Keycloak. Si Keycloak no responde: 503 `SERVICIO_NO_DISPONIBLE`.

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `GET /api/gateway/usuarios?busqueda&page&size` | – | 200 + página de usuarios. `size` entre 1 y 50 |
| `GET /api/gateway/usuarios/{id}` | – | 200 + usuario |
| `POST /api/gateway/usuarios` | `{ usuario, email, nombre, apellido, rol }`, todo obligatorio | 201 + `{ id, contrasenaTemporal }`, con `no-store`. Se muestra una sola vez y Keycloak obliga a cambiarla |
| `PUT /api/gateway/usuarios/{id}/rol` | `{ rol }` | 200 + usuario, con exactamente ese rol |
| `DELETE /api/gateway/usuarios/{id}/rol` | – | 204. El usuario queda sin acceso a módulos internos |
| `PUT /api/gateway/usuarios/{id}/estado` | `{ activo }` | 200 + usuario |

Usuario: `{ id, usuario, email, nombre, apellido, activo, roles }`. Valores de `rol`: `investigador_campo`, `auditor_cientifico`, `cientifico_alto_rango`, `analista_ambiental`, `administrador`.

Un cambio de rol, quitar el rol o desactivar la cuenta invalidan los tokens anteriores desde la siguiente petición (el usuario recibe 401 y debe entrar de nuevo). Un administrador no puede cambiar su propio rol ni desactivarse: 409 `CONFLICTO`.

---

# Parte de Hernando: registro de hallazgos, fichas e historial

Módulo `registro` de `servicio-cientifico` (HU-01, HU-02, HU-03, HU-05). Todas las rutas van bajo
`/api/cientifico/**`, que el gateway ya enruta (§8), y exigen JWT. El rol lo valida además el propio
servicio con `@PreAuthorize` (defensa en profundidad, RNF6).

## 12. Convenciones

- Errores en `application/problem+json` con el mismo formato de §2: `codigo` y `errores[{campo, mensaje}]`.
- Fechas `YYYY-MM-DD`. Identificadores UUID en texto.
- **Ninguna respuesta de registro devuelve coordenadas.** El mapa y las coordenadas exactas son de
  ubicación, que aplica la política por rol (§5.2).

## 13. Endpoints

### 13.1 `POST /api/cientifico/hallazgos` — registrar un hallazgo (HU-01)
Roles: `investigador_campo`, `cientifico_alto_rango` (D63: el administrador no crea ni edita datos científicos).

```json
{
  "nombreCientifico": "Atelopus ebenoides",
  "nombreComun": "Rana arlequín de Boyacá",
  "familia": "Bufonidae",
  "tipoEspecimen": "Adulto",
  "fechaRecoleccion": "2026-03-14",
  "departamento": "Boyacá",
  "categoriaUicn": "EN",
  "descripcion": "Anfibio endémico de páramo…",
  "latitud": 5.53214,
  "longitud": -73.36712,
  "atributos": { "temperatura_agua": 12.5 }
}
```
`201 Created` con `{ "hallazgoId": "...", "version": 1 }` y cabecera `Location`.

| Campo | Obligatorio | Regla |
|---|---|---|
| `nombreCientifico` | Sí | 1–150, sin coordenadas en el texto |
| `familia` | Sí | 1–100 |
| `tipoEspecimen` | Sí | ≤ 80 (HU-01 lo exige; el evento lo admite vacío) |
| `fechaRecoleccion` | Sí | no futura (`America/Bogota`) |
| `departamento` | Sí | del catálogo oficial de 33; se valida antes de guardar y ubicación también lo valida al sincronizar |
| `categoriaUicn` | No | `CR`, `EN`, `VU`, `NT`, `LC`, `DD`, `NE`; por defecto `NE` |
| `nombreComun`, `descripcion` | No | ≤ 150 / ≤ 5000, sin coordenadas en el texto |
| `latitud`, `longitud` | No | ambas o ninguna; se entregan a ubicación y **no** se guardan en registro |

**Equivalencia UICN → `estadoConservacion`** (obligación 5 de §6, ya fijada en pruebas):
`CR`, `EN` → `EN_PELIGRO` · `VU`, `NT`, `LC` → `NO_AMENAZADA` · `DD`, `NE` → `SIN_EVALUAR`.

Errores: `400 DATOS_INVALIDOS` (campo del formulario), `400 UBICACION_INVALIDA` (lo rechazó ubicación;
la transacción se revirtió entera), `403` si el rol no corresponde.

La petición debe enviar `Idempotency-Key` (1–100 caracteres). Un reintento con la misma clave, usuario y
cuerpo devuelve el mismo `hallazgoId` sin crear otra fila; reutilizar la clave con otro cuerpo o usuario
devuelve `409 CLAVE_IDEMPOTENCIA_REUTILIZADA`. La clave se persiste con restricción única para cubrir
reintentos concurrentes. El departamento se valida contra el catálogo antes de guardar.

### 13.2 `PUT /api/cientifico/hallazgos/{id}` — reclasificar (HU-03)
Roles: `investigador_campo`, `cientifico_alto_rango` (D63).

Mismo cuerpo que el alta **sin** `latitud`/`longitud`, más `justificacion` (obligatoria, ≤ 1000). Los
campos nulos no se tocan. Devuelve `{ "hallazgoId": "...", "version": n }` con la versión nueva.

Errores: `400 DATOS_INVALIDOS` (incluye la falta de justificación), `404 HALLAZGO_NO_ENCONTRADO`,
`409 UBICACION_NO_REGISTRADA` o `409 RECLASIFICACION_CONCURRENTE` si otra reclasificación ganó la carrera.

### 13.3 `GET /api/cientifico/hallazgos/{id}` — consultar
Roles: `investigador_campo`, `cientifico_alto_rango`, `auditor_cientifico`, `administrador`.
Devuelve el hallazgo **sin coordenadas**.

### 13.4 `PUT /api/cientifico/fichas/{nombreCientifico}/atributos` — configurar la plantilla (HU-02)
Roles: `investigador_campo`, `cientifico_alto_rango` (D63).

Se envía la lista completa tal como debe quedar; cada llamada crea una **versión nueva** de la plantilla
y las anteriores no se tocan, de modo que los hallazgos ya registrados no se alteran.

```json
[
  { "clave": "temperatura_agua", "etiqueta": "Temperatura del agua", "tipo": "NUMERO",
    "obligatorio": false, "habilitado": true, "unidad": "°C" }
]
```
`clave`: `^[a-z][a-z0-9_]{0,59}$`, estable (renombrar cambia solo `etiqueta`). `tipo`: `TEXTO`,
`NUMERO`, `ENTERO`, `BOOLEANO`, `FECHA`. Retirar un atributo es enviarlo con `habilitado: false`:
nunca se borra, porque los hallazgos antiguos conservan su valor. Máximo 50 por plantilla.

### 13.5 `GET /api/cientifico/fichas/{nombreCientifico}/atributos` — plantilla vigente (HU-02)
Es lo que el formulario usa para dibujar y validar los campos personalizados.
`404 PLANTILLA_NO_ENCONTRADA` si la especie no tiene ninguna.

### 13.6 `GET /api/cientifico/hallazgos/{id}/versiones` — historial (HU-05)
Roles: `auditor_cientifico`, `cientifico_alto_rango`, `administrador`.
Versiones de la más reciente a la más antigua, cada una con su `snapshot`, `justificacion` y `autor`.

### 13.7 `GET /api/cientifico/hallazgos/{id}/auditoria` — cambios campo a campo (HU-05)
Cada fila: `campo`, `valorPrevio`, `valorNuevo`, `justificacion`, `autor`, `fecha`.

### 13.8 `GET /api/cientifico/hallazgos/{id}/comparar?desde=1&hasta=2` — comparar versiones (HU-05)
Devuelve **solo los campos que cambiaron**, que es lo que el auditor necesita resaltar. El orden de los
parámetros no importa. `400` si ambos son iguales; `404` si alguna versión no existe.

### 13.9 Evidencias del hallazgo: fotografías y audios (RF1, HU-01 c.2, D62)

| Método y ruta | Roles | Respuesta |
|---|---|---|
| `POST /api/cientifico/hallazgos/{id}/evidencias` | `investigador_campo`, `cientifico_alto_rango` | `multipart/form-data` con el campo `archivo`. **201** con la evidencia nueva y `Location`; **200** si el mismo archivo ya estaba (no se duplica) |
| `GET /api/cientifico/hallazgos/{id}/evidencias` | los 4 que consultan el hallazgo (todos menos `analista_ambiental`) | Lista en orden de subida |
| `GET /api/cientifico/hallazgos/{id}/evidencias/{evidenciaId}` | los mismos | El archivo, siempre como adjunto (`Content-Disposition: attachment`), con `nosniff` y `no-store` |

Evidencia: `{ id, nombre, tipo: FOTOGRAFIA|AUDIO, contentType, tamanoBytes, sha256, subidoPor, publica, creadoEn }`. Nunca trae la ruta en disco.

`PUT /api/cientifico/hallazgos/{id}/evidencias/{evidenciaId}/publica` con `{ "publica": true|false }` (roles `investigador_campo` y `cientifico_alto_rango`) decide si la foto sale en el portal (D65). Por defecto ninguna es pública. Un audio no puede ser público (**400**).

- **Formatos** (se reconocen por el contenido, no por la extensión): JPEG y PNG (fotos); WAV, MP3, OGG y FLAC (audio). WEBP, HEIC, MP4 y M4A no: pueden traer la ubicación del teléfono y no se limpian.
- **Metadatos:** todo archivo se reescribe antes de guardarse, porque las fotos y las grabaciones pueden traer las coordenadas GPS (RNF9). Fotos: se quitan EXIF, XMP, IPTC, MPF y comentarios, y todo lo que venga pegado después del fin de imagen (el video de una "foto en movimiento", imágenes secundarias). Audio: solo se conserva el sonido (WAV: `fmt`, `fact` y `data`; MP3: solo las tramas de audio; FLAC: `STREAMINFO` y `SEEKTABLE`; OGG Vorbis u Opus de una sola pista: comentarios vacíos). Un archivo que no se puede recorrer completo responde 400. Lo que se descarga es la versión limpia.
- **Tamaño:** máximo 9 MB por archivo (el cuerpo máximo del gateway es 10 MB con el sobre multipart).
- **Errores:** `400 DATOS_INVALIDOS` (vacío, formato no admitido, imagen dañada o sin el campo `archivo`), `413 ARCHIVO_DEMASIADO_GRANDE`, `404 HALLAZGO_NO_ENCONTRADO`, `404 EVIDENCIA_NO_ENCONTRADA` (también si la evidencia es de otro hallazgo), `403`.
- El front sube el hallazgo primero (13.1) y luego cada archivo con el `hallazgoId` que recibió.

### 13.10 Galería de especies, listas para autocompletar y foto pública (D65)

| Método y ruta | Roles | Respuesta |
|---|---|---|
| `GET /api/cientifico/especies?q=&familia=&page=&size=` | todos menos `analista_ambiental` | Página de especies (`size` de 1 a 48, por defecto 12): las de los hallazgos vigentes más las que solo tienen ficha. `q` busca en nombre científico y común |
| `GET /api/cientifico/especies/sugerencias` | los 5 roles | `{ especies: [{ nombreCientifico, nombreComun, familia }], familias: [...], investigadores: [...] }` (tope 1000 cada una) |
| ~~`GET /api/cientifico/publico/especies/foto?nombre=`~~ | — | **Eliminada (D66).** Las fotos públicas salen por el evento `evidencia.publicada` (§7.6) |
| `POST /api/cientifico/admin/fotos-publicas/republicar` | `administrador` | **202** `{ "fotos": n }`. Vuelve a publicar `evidencia.publicada` de todas las fotos marcadas como públicas (genera las miniaturas que falten). Es idempotente del lado del consumidor. Sirve para migrar y para recuperar eventos perdidos |

Especie: `{ nombreCientifico, nombreComun, familia, estadoConservacion, categoriaUicn, registros, departamentos, ultimaRecoleccion, fichaVersion, atributosFicha, foto: { hallazgoId, evidenciaId, publica } | null }`. La foto prefiere una pública; si no hay, la más reciente. Se descarga con §13.9.

### 13.11 `POST /api/cientifico/hallazgos/{id}/versiones/{version}/republicar-evento`
Solo `administrador`. Republica a RabbitMQ el evento a partir del snapshot inmutable de esa versión y
responde `202` con el sobre publicado. Responde `404` si no existe esa versión. Esta operación permite
recuperación manual; el outbox transaccional queda como trabajo pendiente.

## 14. Garantías que registro cumple del contrato

1. El hallazgo se inserta **antes** de avisar a ubicación, en la misma `@Transactional`; si ubicación
   falla, se revierte todo y se responde `400` con el campo (§6, obligación 1).
2. Las coordenadas **no** se guardan en ninguna tabla de registro, ni en el historial, ni en la
   auditoría, ni en el evento público. Los DTO que las transportan enmascaran su `toString()`
   (§6, obligación 2). Hay una prueba de esquema que lo verifica contra MySQL.
3. `hallazgoId` es UUID en `BINARY(16)` y PK de la tabla `hallazgo`, lista para la FK futura de
   ubicación (doc 05 §6).
4. `version` crece estrictamente por hallazgo y es el mismo valor en `UbicacionService` y en el evento.
5. Ningún texto publicable puede contener coordenadas escritas: se valida **antes** de publicar
   (§7.3), con reglas que no confunden fechas, medidas ni altitudes.
6. El evento se publica con `@TransactionalEventListener(AFTER_COMMIT)`, después del commit
   (§7.5, obligación 1). *Pendiente*: outbox transaccional; hoy, si el broker falla tras el commit,
   queda un log de error para republicar.

## 15. Historial inmutable (ASR7) — qué está garantizado hoy

`hallazgo_version` y `hallazgo_auditoria` son append-only **por código y por esquema**:

1. Los repositorios no exponen ninguna operación de modificación ni de borrado.
2. Un `CHECK` impide guardar una versión ≥ 2 sin justificación, y un `UNIQUE` impide repetir una versión.

3. **En la base de datos (D60, desde 2026-11-16):** `cientifico_app` no tiene `UPDATE` ni `DELETE` sobre esas dos
   tablas. Lo aplica `deploy/aiven/03-registro-historial-append-only.sql.example`, que es un paso obligatorio del
   aprovisionamiento de Aiven (`09-despliegue.md` §4.3, paso 5). Un `UPDATE` o `DELETE` sobre el historial falla con
   `ERROR 1142` aunque se use la credencial de la aplicación (HU-05 c.2, RNF10).

Los triggers no sirven: MySQL con binary logging exige `SUPER` para crearlos (ERROR 1419) y ningún usuario del
proyecto lo tiene. Por eso se usan permisos.

**Pruebas:** `MigracionesRegistroTest` crea un usuario con ese reparto y comprueba que el historial rechaza
`UPDATE` y `DELETE` mientras el resto sigue funcionando. `HistorialAppendOnlyScriptTest` hace fallar el build si una
migración crea una tabla que no está en el script: cada tabla nueva tiene que decidir si es historial o si lleva su
línea de permisos.

**Límite:** en el MySQL local de Docker el usuario de la aplicación también hace las migraciones y tiene todos los
permisos, así que el bloqueo solo aplica en Aiven, donde hay un usuario de aplicación y otro de migración.

## 16. Inventario de equipos de campo (HU-04, RF5, RF6) — `servicio-equipos`

Todo exige rol `administrador`. Errores en problem+json: `400 PARAMETRO_INVALIDO`, `404 NO_ENCONTRADO`,
`409 CONFLICTO`, `403 ROL_INSUFICIENTE`, `503 SERVICIO_NO_DISPONIBLE` (no se pudo consultar al gateway).

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `POST /api/equipos` | `{ codigoInventario, nombre, categoria, descripcion? }` | 201 + equipo. 409 si el código ya existe |
| `GET /api/equipos?estado&custodioId&page&size` | – | 200 + `{ content, page, size, total, totalPaginas }`. Los dos filtros se combinan |
| `GET /api/equipos/{id}` | – | 200 + equipo |
| `PUT /api/equipos/{id}/asignacion` | `{ custodioId }` (el `id` de Keycloak) | 200 + equipo `ASIGNADO`. 400 si el custodio no existe, está inactivo o **no es `investigador_campo`** (HU-04 c.1) |
| `PUT /api/equipos/{id}/estado` | `{ estado: DISPONIBLE|MANTENIMIENTO|BAJA }` | 200 + equipo; libera al custodio |

Equipo: `{ id, codigoInventario, nombre, categoria, descripcion, estado, custodioId, custodioUsuario, asignadoEn,
creadoEn, actualizadoEn }`. `ASIGNADO` es el estado "En expedición" de HU-04 c.2: el front lo muestra con ese texto.
Para validar al custodio, el servicio llama a `GET /api/gateway/usuarios/{id}` con el JWT del administrador.

## 17. Reportes (HU-09, RF15, RF16) — `servicio-reportes`

Roles: `analista_ambiental` y `administrador`. Todo se calcula sobre la base analítica, alimentada por los eventos
`hallazgo.*`; nunca se consulta la base del Servicio Científico (ASR3).

### 17.1 Reporte mensual asíncrono de presión sobre los ecosistemas (SD-08, D61)

| Método y ruta | Respuesta |
|---|---|
| `POST /api/reportes/solicitudes` con `{ "periodo": "2026-03" }` | **202 Accepted** + `Location` + la solicitud en `PENDIENTE`. El reporte se genera en segundo plano (HU-09 c.1) |
| `GET /api/reportes/solicitudes` | Las 20 más recientes |
| `GET /api/reportes/solicitudes/{id}` | Estado: `PENDIENTE`, `EN_PROCESO`, `LISTO` o `FALLIDO`. Con `LISTO` trae `resumen` |
| `GET /api/reportes/solicitudes/{id}/csv` · `/pdf` | El archivo (HU-09 c.3). **409 `REPORTE_NO_LISTO`** si todavía no está listo. Se arma al descargar desde el resumen guardado, así que un reporte viejo sale con el formato vigente. El CSV va para Excel en español: separador `;`, coma decimal, UTF-8 con BOM y encabezados legibles (`Departamento;Hallazgos;En peligro;…;Zona en alerta`) |

Solicitud: `{ id, periodo, estado, origen: MANUAL|PROGRAMADO, solicitadoPor, solicitadoEn, iniciadoEn, terminadoEn,
error, resumen }`. `resumen` es `{ periodo, totalHallazgos, totalEnPeligro, departamentos, zonasEnAlerta: [...],
indices: [{ departamento, hallazgos, enPeligro, especies, proporcionEnPeligro, promedioEnPeligroPrevio, crecimiento,
indice, nivel, alerta }] }`, ordenado del departamento más presionado al menos. La fórmula está en D61.

- `periodo` en formato `AAAA-MM`, entre 2000 y el mes actual: si no, 400 `PARAMETRO_INVALIDO`.
- El front consulta el estado cada 1 o 2 segundos hasta `LISTO` o `FALLIDO` y entonces muestra los botones de descarga.
- El primer día de cada mes, a las 3 a. m. de Bogotá, el servicio pide solo el reporte del mes anterior
  (`origen: PROGRAMADO`, `solicitadoPor: sistema`), una sola vez por mes (HU-09 "programar").

### 17.2 Consultas inmediatas (las de antes)

`GET /api/reportes/resumen`, `GET /api/reportes/hallazgos` y `GET /api/reportes/exportar/{csv|pdf}`, con los filtros
opcionales `departamento`, `familia`, `estado`, `desde` y `hasta`. Responden en el momento con conteos por
departamento, familia y estado. Sirven para explorar; el reporte mensual de HU-09 es el de 17.1.
