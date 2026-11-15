# Módulo de ubicación (D17)

Dos pantallas autocontenidas: `PaginaBusqueda` (HU-13) y `PaginaMapa` (HU-07, Leaflet). No usan el router ni el login: reciben lo que necesitan por props. Estilos propios con prefijo `ub-` (no tocan `index.css`).

## Cómo montarlas (Santiago)

```jsx
import { PaginaBusqueda, PaginaMapa, crearClienteUbicacion } from './ubicacion/index.js';
import { keycloak, rolesUsuario } from './auth/keycloak.js';
import { config } from './config.js';

// Crear UNA vez (fuera del componente o con useMemo): debe tener identidad estable.
const cliente = crearClienteUbicacion({
  apiUrl: config.apiUrl,
  obtenerToken: async () => { await keycloak.updateToken(30); return keycloak.token; },
});

// Rutas propuestas: /ubicacion/busqueda y /ubicacion/mapa
<PaginaBusqueda
  cliente={cliente}
  roles={rolesUsuario()}
  alSesionVencida={() => keycloak.login()}
  alVerEnMapa={(id) => irA(`/ubicacion/mapa?hallazgo=${id}`)}   // opcional
/>

<PaginaMapa
  cliente={cliente}
  roles={rolesUsuario()}
  hallazgoId={idDeLaUrl}                       // opcional (query ?hallazgo=<id>)
  alSesionVencida={() => keycloak.login()}
  alIrABusqueda={() => irA('/ubicacion/busqueda')}
/>
```

- `cliente` es cualquier objeto `{ get(ruta, { signal }) => Promise<json>, getBlob(ruta, { signal, sinCache }) => Promise<Blob> }` que rechace con un error con `status` (y `codigo` si lo conoce; `0` = sin respuesta; el `AbortError` se propaga). `crearClienteUbicacion` ya lo implementa con `fetch` (Bearer, sin cookies; `get` con `cache: 'no-store'`, `getBlob` con `no-store` solo si `sinCache: true`, es decir desde el zoom 9, D57; por debajo el navegador reutiliza las teselas). Un cliente propio debe implementar también `getBlob` y respetar `sinCache` (si lo ignora, reabre SEC-J-24); sin él el mapa base no carga y la pantalla pasa a la vista Lista. En tests se puede inyectar uno falso.
- Con `sinCache: true` (teselas de zoom 9 o más, `ZOOM_SIN_CACHE`) el cliente no debe leer ni guardar la respuesta en la caché del navegador; `crearClienteUbicacion` usa `cache: 'no-store'` (SEC-J-24).
- `roles`: roles de negocio (`rolesUsuario()`). Solo `cientifico_alto_rango` ve coordenadas y entra al mapa. Cualquier otro rol (o lista vacía) ve "Restringido" y, en el mapa, la pantalla de acceso denegado **sin llamar a la API**.
- El enlace "Mapa" puede mostrarse a todos los roles: la pantalla explica el rechazo.
- Las pantallas cambian `document.title` y mueven el foco al título al entrar.
- Las dependencias `leaflet` y `react-leaflet` ya están en `package.json`.
- **Teselas del mapa (SEC-J-04, D56).** El navegador nunca contacta a OpenStreetMap: `componentes/CapaTeselasGateway.js` pide cada tesela a `GET /api/gateway/teselas/{z}/{x}/{y}.png` con `cliente.getBlob` (el cliente añade `apiUrl` y el JWT) y la muestra desde un object URL. Cada tesela tiene su `AbortController`: se aborta al descartarla Leaflet (zoom, desplazamiento, quitar el mapa) y el object URL se revoca siempre. Un fallo de tesela cuenta para el aviso "No se pudo cargar el mapa base" (más de 3); un aborto no. Un 401 de tesela llama una vez a `alSesionVencida`. No hace falta ninguna prop nueva en `PaginaMapa`.
- **CSP** (si se agrega, SEC-J-07): `img-src 'self' data: blob:` (Leaflet pone un GIF `data:` en cada tesela descartada, SEC-J-I24) y `connect-src` hacia el gateway. Ya no hace falta permitir `tile.openstreetmap.org`. Se mantienen `maxZoom: 12` y la atribución visible de OpenStreetMap.
- Las coordenadas solo viven en el estado de React: nunca en URL, almacenamiento, título ni consola.

## Pruebas

`npm test` (carpeta `pruebas/`, sin backend: cliente falso y `react-leaflet` simulado).

## Arnés de desarrollo (D58)

Página propia para abrir las pantallas en un navegador real sin esperar el montaje en `App.jsx`: `dev/arnes.html` (+ `arnes.jsx`, `Arnes.jsx`, `sesionReal.js`, `sesionSimulada.js`). Monta `PaginaBusqueda` y `PaginaMapa` con `crearClienteUbicacion` igual que el bloque "Cómo montarlas" y navega entre ellas sin router. Muestra un aviso fijo de "Solo desarrollo".

```bash
npm run dev:ubicacion        # abre http://localhost:5173/src/ubicacion/dev/arnes.html
```

- **Modo real (predeterminado):** login con Keycloak a través de `src/auth/keycloak.js` (sin modificarlo), token con `keycloak.updateToken(30)`, `apiUrl` de `src/config.js`, botón "Cerrar sesión". Un 401 llama a `keycloak.login()`.
- **Modo simulado (solo Playwright):** `?modo=simulado&roles=cientifico_alto_rango` (lista separada por comas; vacía = sin rol). Token falso `token-simulado-e2e`; `&token=vencido` simula que no se puede renovar. No contacta a Keycloak; la API es la misma URL del gateway y la prueba la intercepta con `page.route`. Un 401 no redirige: el arnés muestra "Se pidió iniciar sesión de nuevo (N)".
- Otros parámetros: `pantalla=mapa` abre el mapa y `hallazgo=<id>` lo abre con un solo hallazgo.

**Por qué no llega a producción:** `npm run build` solo usa `index.html` como entrada y nadie importa `dev/` desde el módulo (`index.js` no lo exporta), así que `dist/` no contiene nada del arnés. Además, la sesión simulada solo se carga dentro de `if (import.meta.env.DEV)` y `crearSesionSimulada` vuelve a comprobarlo: incluso un build forzado de `arnes.html` (verificado a mano) solo deja el mensaje "El arnés de ubicación solo funciona en desarrollo", sin modo simulado ni Keycloak.

## Pruebas E2E (Playwright)

Specs en `e2e/*.e2e.js` (el sufijo `.e2e.js` evita que Vitest los recoja; no hace falta tocar `vite.config.js`). Config: `web-interna/playwright.config.js`. Levantan `vite` en el puerto 5175 y abren el arnés en modo simulado; el gateway (búsqueda, mapa y `/api/gateway/teselas/**`, que devuelve un PNG válido) se intercepta con `page.route` (`e2e/fixtures.js`). Toda petición fuera de `localhost` se aborta y queda registrada. No hace falta backend.

```bash
npx playwright install chromium firefox webkit   # en Linux con WebKit: npx playwright install --with-deps
npm run test:e2e                                  # Chromium, Firefox y WebKit
E2E_SIN_WEBKIT=1 npm run test:e2e                 # sin WebKit (equipos sin sus librerías del sistema)
```

| Archivo | Casos |
|---|---|
| `busqueda.e2e.js` | WI-01 (filtros, paginación, tamaño), WC-07 en navegador, WI-02 (4 roles + sin rol), WI-03, WI-04 (401 y token no renovable), WI-12 (500, 503, sin red, 400), WI-09 |
| `mapa.e2e.js` | WI-05 (marcadores, ratón, teclado Enter/Esc, Lista, filtros), WI-06 (4 roles + sin rol, 0 peticiones), WC-03, WI-07, WI-08, WI-12, WI-04 y 401 de teselas, mapa base caído (429/503/sin red), WI-14 |
| `seguridad.e2e.js` | WI-15 (red completa sin OSM, Bearer, zoom ≤ 12, `no-store` desde z9, 429 sin respaldo a OSM), WI-10 |

**Qué se afirma sobre la caché de teselas (D57) en cada motor.** Playwright desactiva la caché HTTP mientras hay rutas interceptadas, así que la caché real del disco no se puede observar en estas pruebas. Lo que se comprueba es la opción `cache` con que la app llama a `fetch`, con un espía instalado antes de cargar la página: `'no-store'` desde el zoom 9 y sin opción hasta el zoom 8. Eso es igual en los tres motores. Las cabeceras de petición solo se registran como anotación: Chromium y Firefox no muestran `Cache-Control` ni `Pragma` en estas peticiones, y WebKit pone `no-cache` en todas, también en las de zoom ≤ 8. En ningún motor sirven para distinguir el zoom. Que la tesela no quede en disco se verifica a mano (abajo).

## Guía manual: modo real y cierre de SEC-J-04 / SEC-J-24

Requisitos: `.env` de la raíz con las contraseñas (README de la raíz, sección de entorno).

1. Infraestructura: en la raíz, `docker compose up -d` y esperar a que `docker compose ps` muestre todo `healthy` (Keycloak tarda entre 30 y 60 s).
2. Backend, un servicio por terminal: `cd api-gateway && mvn spring-boot:run` (8080) y `cd servicio-cientifico && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run` (8081). Para que haya marcadores debe haber hallazgos con coordenadas en la base local.
3. Front: `cd web-interna && npm run dev:ubicacion`. Si el navegador no se abre solo, entra a `http://localhost:5173/src/ubicacion/dev/arnes.html?pantalla=mapa`. Tiene que ser el puerto 5173, que es el que acepta Keycloak.
4. Inicia sesión con `cientifico1` (la contraseña es `KEYCLOAK_TEST_USERS_PASSWORD` de tu `.env`). La cabecera del arnés debe decir "Modo: real (Keycloak)" y "Roles: cientifico_alto_rango".
5. Abre DevTools (F12), pestaña **Red / Network**. **No** marques "Disable cache". Recarga la página con el mapa abierto.
   - Escribe `openstreetmap` en el filtro: debe haber **0 peticiones**. Quita el filtro y ordena por dominio: todo va a `localhost:5173`, `localhost:8180` (Keycloak) o `localhost:8080` (gateway).
   - Escribe `teselas` en el filtro: cada petición es `GET http://localhost:8080/api/gateway/teselas/{z}/{x}/{y}.png`, de tipo `fetch`, con estado `200`, `Content-Type: image/png` y la cabecera de petición `Authorization: Bearer …`. Ninguna es de tipo `img` hacia otro host.
6. Caché del navegador (SEC-J-24, D57):
   - Aléjate hasta el zoom 8 o menos (país o departamento), acércate y vuelve a alejarte. Las teselas de zoom ≤ 8 que se repiten pueden aparecer como `(disk cache)` o `(memory cache)` en la columna Tamaño/Transferido. Es lo esperado (`private, max-age=86400`).
   - Acércate a zoom 9 o más (la ruta muestra el primer número: `/teselas/9/…` hasta `/teselas/12/…`), desplázate y vuelve a una zona ya vista. Ninguna tesela de z ≥ 9 debe aparecer como `(disk cache)` ni `(memory cache)`: siempre tiene tamaño transferido. Según el navegador, la petición puede llevar además `Cache-Control: no-cache` y `Pragma: no-cache`, que es lo que la especificación Fetch agrega con `cache: 'no-store'`. Lo que cuenta es que nunca aparezca servida desde caché.
   - Firefox (opcional): `about:cache?storage=disk` no debe listar URLs `/api/gateway/teselas/9/…` a `/12/…` (las de z ≤ 8 sí pueden aparecer).
7. Zoom máximo (WI-14): al llegar a 12 el botón "+" queda deshabilitado y no aparece ninguna petición `/teselas/13/…`.
8. Roles (WI-13): cierra sesión y entra con `investigador1` (y `auditor1`, `analista1`, `admin1`, `sinrol1`). El mapa muestra "No tienes permiso para ver esta información" y la pestaña Red no tiene ninguna petición a `/api/cientifico/ubicacion/mapa` ni a `/teselas/`. En la búsqueda, la columna Coordenadas dice "Restringido" (con `sinrol1` el gateway responde 403 y la búsqueda muestra el acceso denegado).
9. Límite de teselas (opcional): con zoom y desplazamiento muy rápidos durante un minuto (más de 600 teselas), el gateway responde 429. El mapa muestra "No se pudo cargar el mapa base. Los hallazgos siguen disponibles en la vista Lista." y pasa a Lista sin pedir nada a OSM.

Si los pasos 5 y 6 se cumplen, se pueden marcar como cerrados en navegador real SEC-J-04 (WI-15) y SEC-J-24.
