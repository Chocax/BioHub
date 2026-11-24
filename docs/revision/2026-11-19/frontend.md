# Revisión frontend (web-interna y web-publica), 2026-11-19

**Rama:** `develop` (HEAD `8d23981`). **Modo:** solo lectura. No se ejecutó build, tests ni servidores (qa-tester los corre). El `dist/` que hay en disco es viejo (4 y 15 de noviembre), así que no se usó para medir el bundle.
**Alcance leído:** `api/cliente.js`, `auth/*`, `main.jsx`, `App.jsx`, `Marco.jsx`, `ui.jsx`, `Foto.jsx`, `sugerencias.js`, páginas de `web-interna`; `api/*`, `Inicio`, `Ficha`, `FotoEspecie`, `AvisoUbicacionProtegida`, `index.html` y `vite.config.js` de `web-publica`; inventario de tests.

## Resumen
Calificación: **7,5 / 10**.

| Severidad | Cantidad |
|---|---|
| Crítico | 0 |
| Alto | 0 |
| Medio | 6 |
| Bajo | 7 |

## Hallazgos

### FE-R-1 (Medio) Foto pública: cualquier fallo (429, 5xx) se presenta como "sin foto pública"
- **Dónde:** `web-publica/src/components/FotoEspecie.jsx:21`, y `web-publica/src/api/especies.js` (`urlFotoEspecie`).
- **Problema:** el `<img onError>` no distingue un 404 (no hay foto) de un 429 o 503. Una página del catálogo dispara 12 peticiones de foto por el gateway, además de la búsqueda. Si el límite público las rechaza con 429, o la caché del backend falla, la tarjeta dice "Esta especie todavía no tiene foto pública" y queda así hasta recargar. En `<img>` no se puede leer el estado ni `Retry-After`.
- **Por qué importa:** es información falsa y no se recupera. Además las fotos consumen la cuota por minuto de la búsqueda (RNF11) y ensucian la consola con 404 esperados.
- **Corrección:** pedir la foto con `fetch` (`credentials: 'omit'`) y blob URL, o que el catálogo (`/api/publica/especies`) devuelva un `tieneFoto` booleano para no pedir las que no existen. Mientras tanto, cambiar el `aria-label` a "Sin foto disponible" y permitir reintento.
- **Esfuerzo:** M.

### FE-R-2 (Medio) Descargas de reportes sin manejo de error
- **Dónde:** `web-interna/src/paginas/Reportes.jsx:60-61`, que llama a `descargar()` de `api/cliente.js:81`.
- **Problema:** `onClick={() => descargar(...)}` no tiene `catch`. Un 404 (archivo no generado), 403, 429 o 5xx produce una promesa rechazada sin capturar. La persona pulsa y no pasa nada; no hay estado "descargando".
- **Por qué importa:** HU-09 c.3 es la salida principal del analista, y falla en silencio.
- **Corrección:** envolver en un manejador con estado (`descargando`, `error`) y mostrar `AvisoError`; desactivar el botón durante la descarga.
- **Esfuerzo:** S.

### FE-R-3 (Medio) `web-interna` sin Content-Security-Policy
- **Dónde:** `web-interna/index.html` (no hay `<meta http-equiv="Content-Security-Policy">`), mientras `web-publica/index.html` sí la tiene (SEC-J-07).
- **Problema:** la web interna maneja el JWT en memoria y datos con coordenadas exactas, y no tiene CSP. Un script inyectado (por ejemplo vía una dependencia) podría leer `keycloak.token`.
- **Por qué importa:** es la superficie más sensible del sistema y la menos protegida. La decisión D64 menciona la CSP solo para la pública. Puede que la cabecera la ponga el servidor de despliegue; en el repo no se ve.
- **Corrección:** CSP por cabecera en el despliegue (o `<meta>` con el plugin de `web-publica`). Debe permitir `connect-src` hacia API y Keycloak, `frame-src` Keycloak y `img-src blob: data:`. Ojo: `Foto.jsx` usa `blob:` y el mapa usa teselas por el gateway.
- **Esfuerzo:** M.

### FE-R-4 (Medio) Roles de la sesión congelados al iniciar
- **Dónde:** `web-interna/src/main.jsx:36` (`roles: rolesUsuario()` se calcula en cada render de `Raiz`, pero `Raiz` no se vuelve a renderizar al refrescar el token), `auth/keycloak.js:46`.
- **Problema:** si un administrador cambia el rol de alguien (`Usuarios.jsx` dice que sus sesiones se cierran), el menú y las guardas se quedan con los roles anteriores hasta recargar. Tras `updateToken` el token trae roles nuevos, pero la UI no se entera.
- **Por qué importa:** el backend sí rechaza (403), así que no es una brecha. Pero la persona ve menús que ya no funcionan o no ve los nuevos.
- **Corrección:** `keycloak.onTokenExpired`/`onAuthRefreshSuccess` que actualicen un estado con los roles, y ante un 403 en la UI refrescar el token una vez.
- **Esfuerzo:** S.

### FE-R-5 (Medio) Pinturas grandes en la web interna y primera imagen de portada pública pesada
- **Dónde:** `web-interna/public/pinturas/tequendama.webp` (783 KB), `andes.webp` (337 KB), `pasiflora.webp` (322 KB), `rio.webp` (275 KB); `web-publica/public/pinturas/andes-grande.webp` (489 KB), `rio-grande.webp` (421 KB), `nubes-grande.webp` (333 KB).
- **Problema:**
  - En la interna, `PINTURAS[x].src` (el archivo grande) está definido pero el código usa casi solo `.chica` (`-800`, 30 a 155 KB). Los grandes pesan en el despliegue sin servirse.
  - En la pública, `CieloRotativo` (`paginas/Inicio.jsx:41-49`) pone cuatro `<img>` a la vez. Las tres últimas usan `loading="lazy"` pero están con `opacity:0`, y el navegador puede cargarlas igual. En pantallas de 1x a 1440 px baja el `srcset` de 2400 w.
  - La portada puede llegar a ~1,2 MB de imágenes de fondo, además de las 12 fotos del catálogo.
- **Por qué importa:** el portal público es el de mayor tráfico y debe abrir rápido. Los 25 s de rotación con carga eager de todo compiten con el LCP.
- **Corrección:** cargar la siguiente pintura solo cuando le toca (renderizar solo la actual y la siguiente), y borrar los `.webp` grandes que no se usan en la interna (o dejar un 1600 px). Medir con Lighthouse; un `-1600` intermedio en `srcset` ayuda.
- **Esfuerzo:** S a M.

### FE-R-6 (Medio) Pantallas nuevas sin ningún test de componente
- **Dónde:** `web-interna/src/paginas/` (`NuevoHallazgo`, `DetalleHallazgo`, `Historial`, `Plantillas`, `Equipos`, `Reportes`, `Usuarios`, `ApiKeys`) y `componentes/Foto.jsx`, `api/cliente.js`.
- **Problema:** `web-interna/src/App.test.jsx` tiene 8 casos (portada, menú por rol, sala ajena, equipos del administrador, 404, matriz de permisos). Ninguna otra página de `paginas/` tiene prueba, ni `cliente.js` (401, 403, 429 con `Retry-After`, 5xx, `AbortError`), ni la caché y revocación de `Foto.jsx`, ni el flujo de contraseña temporal y API Key de un solo uso. Las pantallas con mayor riesgo (Usuarios: cambio de rol, desactivar; ApiKeys: emitir y revocar; NuevoHallazgo: subida multipart y tope de 9 MB) están sin cubrir. El módulo de ubicación sí tiene 5 archivos de test y 3 E2E (`busqueda`, `mapa`, `seguridad`).
- **Por qué importa:** son las pantallas escritas en los commits recientes (PR #8 a #17) y concentran la lógica de escritura.
- **Corrección:** tests de `cliente.js` (mock de `fetch` y `keycloak`), y un test por página con `vi.mock` de `api`. Prioridad: Usuarios, ApiKeys, NuevoHallazgo, Reportes.
- **Esfuerzo:** M a L.

### FE-R-7 (Bajo) Guarda de la ruta `/mapa` distinta al resto
- **Dónde:** `web-interna/src/App.jsx:104`.
- **Problema:** todas las rutas usan `<Con capacidad=...>` salvo `mapa`, que cae directo en `PaginaMapa`. Esta valida el rol dentro (`esAltoRango` y `AccesoDenegado`, `PaginaMapa.jsx:24,69`), así que funciona, pero hay dos mecanismos y `puede(roles,'verMapa')` queda sin usar en la ruta.
- **Corrección:** envolver en `<Con capacidad="verMapa">` y dejar el control interno como segunda capa. Ya hay test de la matriz.
- **Esfuerzo:** S.

### FE-R-8 (Bajo) `fetch` sin tiempo límite
- **Dónde:** `web-interna/src/api/cliente.js:55`, `web-publica/src/api/cliente.js:17`.
- **Problema:** si el gateway se cuelga (no cae), la pantalla queda en "Cargando…" sin fin. Con el circuit breaker del backend esto es raro, pero no hay tope en el cliente.
- **Corrección:** `AbortSignal.timeout(15000)` combinado con la señal del llamador (`AbortSignal.any`), y mapear a `ErrorApi(0)`.
- **Esfuerzo:** S.

### FE-R-9 (Bajo) Mensaje del servidor mostrado tal cual
- **Dónde:** `web-interna/src/api/cliente.js:11` (`cuerpo.detail || cuerpo.detalle`) y `Usuarios.jsx` (`error.message`).
- **Problema:** se pinta el `detail` del problem+json. React escapa el texto, así que no hay XSS, pero un `detail` técnico (de un 500 o de una excepción) llegaría al usuario.
- **Corrección:** para `status >= 500` usar siempre el mensaje genérico y mostrar `detail` solo en 400, 409 y 422. Confirmar con backend que `detail` no lleva trazas.
- **Esfuerzo:** S.

### FE-R-10 (Bajo) Evicción de la caché de fotos puede revocar una URL en uso
- **Dónde:** `web-interna/src/componentes/Foto.jsx:19-20`.
- **Problema:** al pasar de 80 fotos se revoca la más antigua (FIFO), aunque siga montada en una galería larga (`Plantillas`, tabla de hallazgos). El `<img>` ya cargado no se rompe, pero si se vuelve a renderizar con la URL revocada muestra imagen rota. La caché tampoco se vacía al cerrar sesión (`cerrarSesion`), así que fotos internas quedan como blob en memoria hasta recargar.
- **Corrección:** LRU (reordenar al leer) y `limpiarCache()` en el cierre de sesión.
- **Esfuerzo:** S.

### FE-R-11 (Bajo) Campo `descripcion` público se renderiza sin filtro propio
- **Dónde:** `web-publica/src/paginas/Ficha.jsx:130`, `api/fichas.js` (`descripcion` está en la lista blanca).
- **Problema:** la lista blanca bloquea campos de coordenadas, pero `descripcion` es texto libre del investigador y podría escribir "10,23 N 74,12 W" o un enlace a un mapa. El front lo muestra tal cual. Esa es una fuga por contenido, no por esquema.
- **Por qué importa:** RNF7 y D14 (nunca mostrar coordenadas exactas). La defensa real debe estar en el backend (validar o limpiar al publicar la ficha); el front puede aportar una segunda barrera.
- **Corrección:** confirmar con backend que la ficha pública se valida. Opcional: en el front, ocultar la descripción si coincide con un patrón de coordenadas (decimales con grados o N/S/E/W).
- **Esfuerzo:** S (front) / M (backend).

### FE-R-12 (Bajo) "Otros registros" limitado a 20 resultados de una búsqueda difusa
- **Dónde:** `web-publica/src/paginas/Ficha.jsx:28`.
- **Problema:** `buscarFichas({ q: nombreCientifico })` trae la primera página (20) y filtra por nombre exacto en el cliente. Si hay coincidencias parciales de otras especies o más de 20 registros, se pierden. Además cada ficha hace una consulta extra contra el límite público.
- **Corrección:** usar `GET /api/publica/especies` (que ya agrupa departamentos) o un endpoint por nombre exacto.
- **Esfuerzo:** S a M.

### FE-R-13 (Bajo) Accesibilidad menor
- **Dónde:** `Usuarios.jsx:108`, `ApiKeys.jsx:57`, `ApiKeys.jsx:88`, `Marco.jsx`.
  - `window.confirm` (3 usos) bloquea y se anuncia distinto según el lector; mejor un diálogo accesible (`<dialog>`).
  - Los botones "Copiar" no avisan el resultado: en ApiKeys no hay `aria-live` ni cambio de texto (Usuarios sí lo cambia), y `clipboard` no tiene `catch`.
  - `Foto.jsx:46` pone `aria-hidden` al contenedor mientras la foto carga; está bien, pero `FotoEvidencia` con `alt=""` en `Plantillas.jsx:69` deja la tarjeta sin nombre si no hay texto cercano.
  - No se pudo medir contraste (sin navegador). Revisar `texto-suave` sobre masilla y `cielo__eyebrow` sobre pintura con axe o Lighthouse.
- **Esfuerzo:** S a M.

## Verificación por tema

**Contrato API.** Rutas coherentes con D65 y el contrato: `/api/cientifico/especies/sugerencias`, `/api/publica/especies`, `/api/publica/especies/familias`, `/api/publica/especies/foto`, `/api/gateway/*`, `/api/reportes/solicitudes/{id}/pdf|csv`. 401: `pedir` llama a `keycloak.login()` y lanza `ErrorApi(401)`. 403: mensaje de rol, y las páginas de ubicación muestran `AccesoDenegado`. 429: se lee `Retry-After` y `AvisoError` dice "Intenta de nuevo en N s" (pública: cuenta regresiva de 10 s fija, no usa `reintentarEn`; ver nota). 5xx y red: mensajes genéricos. 204 y `AbortError` bien tratados. Sin tiempo límite (FE-R-8).
Nota: `ErrorDeConsulta` (pública) ignora `error.reintentarEn` y cuenta siempre 10 s; debería usar el `Retry-After` real (Bajo, S).

**Token.** Authorization Code + PKCE S256, token solo en memoria, sin `localStorage`/`sessionStorage` (grep limpio; solo el E2E `seguridad.e2e.js` lo comprueba). `updateToken(30)` antes de cada petición, `onAuthRefreshError` lleva al login, `checkLoginIframe:false`. Fotos y descargas se piden con Bearer y blob (no se pone el token en URL). Sin `dangerouslySetInnerHTML` ni `innerHTML` en el código (grep limpio).

**Roles en la UI.** `permisos.js` replica la matriz de 11 §2 y D63 (el administrador no registra ni reclasifica; el mapa solo para alto rango). Aclara que el backend es quien autoriza; la UI es solo presentación. Cada ruta (salvo `/mapa`, FE-R-7) se protege con `Con`, y `SinPermiso` no llama a la API. Hay test de la matriz.

**Coordenadas en la web pública.** No aparecen en ningún componente: la lista blanca (`aFichaPublica`, `aEspeciePublica`) descarta cualquier otro campo, no hay Leaflet ni inputs de latitud o longitud, y `AvisoUbicacionProtegida` se muestra siempre, aunque el servidor diga otra cosa. Las fotos llegan sin EXIF (JPEG recodificado, D65). Único punto a vigilar: `descripcion` libre (FE-R-11) y que quien marca una foto como pública revise que no muestre el lugar (consecuencia aceptada en D65).

**Estados.** Las dos webs tienen cargando (esqueleto, `role="status"`), vacío, error con reintento, y modo degradado en la pública. `aria-busy`, foco al título tras paginar, `useTitulo`. Faltan los estados de error de descarga (FE-R-2).

**Rendimiento.** Rutas perezosas en la interna (Leaflet solo al entrar al mapa), `loading="lazy"`, `srcset` en la pública, fuentes locales con `@fontsource`. Imágenes: ver FE-R-5. Bundle sin medir (no se corrió el build).

**Accesibilidad.** `lang="es"`, enlace "Saltar al contenido", `:focus-visible` con contorno de 2-3 px, `prefers-reduced-motion` en ambas, `Campo` con `label`/`aria-describedby`/`aria-invalid`, `role="alert"` en errores, `alt` descriptivo en fotos y `alt=""` en pinturas decorativas, tablas con `caption`, menú móvil con `aria-expanded`. Pendiente: FE-R-13.

**Responsive.** Menú plegable (`marco__menu`), bordes decorativos de sala solo en pantallas anchas, rejillas con `minmax`. Sin probar en navegador.

## Tests existentes

| Web | Tests de componente | E2E |
|---|---|---|
| interna | `App.test.jsx` (8) y 5 archivos de `ubicacion/pruebas` (mapa, búsqueda, teselas, API) | Playwright, 3 archivos de ubicación (`busqueda`, `mapa`, `seguridad`) con Keycloak simulado en el arnés |
| pública | `App.test.jsx`, `Inicio.test.jsx`, `Ficha.test.jsx` | `e2e/portal.spec.js` con `fixtures.js` |

**Sin test:** en la interna, `Inicio`, `NuevoHallazgo`, `DetalleHallazgo`, `Historial`, `Plantillas`, `Equipos`, `Reportes`, `Usuarios`, `ApiKeys`, `Marco`, `Foto`, `cliente.js`, `permisos` (salvo la matriz). Sin E2E de ninguna página fuera del módulo de ubicación (registro, usuarios, API Keys, reportes). En la pública, `FotoEspecie`, `FiltrosAvanzados`, `Paginacion`, `TarjetaEspecie` (cubiertos de forma indirecta por `Inicio.test`).

## Bien hecho
- Capa de servicios única y pequeña (`pedir`/`api`/`descargar`) con `ErrorApi` que conserva `status`, `codigo`, `errores[]` y `Retry-After`.
- Token solo en memoria, PKCE S256, refresco previo a cada petición y login ante sesión vencida.
- Lista blanca de campos en la web pública, aviso de ubicación protegida siempre visible y sin ningún componente de mapa ni coordenadas.
- La CSP de la pública sin `unsafe-inline` y sin CDN (fuentes e imágenes propias), con el origen de la API inyectado por plugin de Vite.
- Menú, rutas y guardas por rol alineados con la matriz y D63, con la advertencia explícita de que el backend es la autoridad.
- Rutas perezosas, `AbortController` en las consultas de la pública, `credentials: 'omit'`, `cache: 'no-store'` en la interna.
- Cuidados de formulario: `Campo` accesible, validación de `q` con las mismas reglas del servidor, tope de 9 MB y aviso de que se quita el GPS de las fotos.
- Secretos de un solo uso (contraseña temporal y API Key) mostrados una vez y con confirmación antes de acciones destructivas.
- Respeto por `prefers-reduced-motion` y por el modo degradado del servidor (filtros desactivados con aviso).

## Calificación: 7,5 / 10
Sin hallazgos Críticos ni Altos: el manejo del token, el control de acceso y la protección de coordenadas están bien resueltos. Restan puntos por la falta de tests en las pantallas de escritura nuevas, la ausencia de CSP en la interna, el manejo de errores de fotos y descargas, y el peso de las imágenes.
