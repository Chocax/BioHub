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
