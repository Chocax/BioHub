import { test as base, expect } from '@playwright/test';

/**
 * Simulación del gateway para el arnés (D58). Datos con la forma de 04-contrato-api.md §5.1, §5.2 y §8.1.
 * Las coordenadas son centinelas fáciles de buscar en DOM, URL, almacenamiento y consola (WC-01, WI-10).
 */
export const API_URL = 'http://localhost:8080';
export const TOKEN_SIMULADO = 'token-simulado-e2e'; // el mismo de src/ubicacion/dev/sesionSimulada.js
export const RUTA_ARNES = '/src/ubicacion/dev/arnes.html';

export const ROL_ALTO = 'cientifico_alto_rango';
export const OTROS_ROLES = ['investigador_campo', 'auditor_cientifico', 'analista_ambiental', 'administrador'];

/** Tres hallazgos con coordenadas centinela (lat, lon). */
export const HALLAZGOS = [
  {
    id: '3f2a9c1e-5b7d-4e8a-9f10-2c3d4e5f6a7b',
    nombreCientifico: 'Atelopus ebenoides',
    fechaRecoleccion: '2026-03-14',
    investigador: 'investigador1',
    departamento: 'Boyacá',
    departamentoCodigo: '15',
    region: 'Andina',
    estadoConservacion: 'EN_PELIGRO',
    latitud: 5.1234567,
    longitud: -73.1234567,
  },
  {
    id: '8b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d',
    nombreCientifico: 'Atelopus muisca',
    fechaRecoleccion: '2026-02-02',
    investigador: 'investigador2',
    departamento: 'Cundinamarca',
    departamentoCodigo: '25',
    region: 'Andina',
    estadoConservacion: 'NO_AMENAZADA',
    latitud: 4.7654321,
    longitud: -74.0456789,
  },
  {
    id: 'c4d5e6f7-0a1b-4c2d-8e3f-4a5b6c7d8e9f',
    nombreCientifico: 'Oophaga histrionica',
    fechaRecoleccion: '2026-01-20',
    investigador: 'investigador1',
    departamento: 'Chocó',
    departamentoCodigo: '27',
    region: 'Pacífica',
    estadoConservacion: 'SIN_EVALUAR',
    latitud: 5.6912345,
    longitud: -76.6543219,
  },
];

/** Formas en que una coordenada centinela podría filtrarse (crudo y con 5 decimales, como la UI). */
export const CENTINELAS = HALLAZGOS.flatMap((h) => [
  String(h.latitud),
  String(h.longitud),
  h.latitud.toFixed(5),
  h.longitud.toFixed(5),
]);

/** PNG válido de 1x1 (gris). Leaflet lo estira al tamaño de la tesela. */
export const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mN4+P/ffwAI9AO/v9QeNQAAAABJRU5ErkJggg==',
  'base64',
);

export function problema(status, codigo, detail, instance) {
  return { type: 'about:blank', title: 'Error', status, detail, instance, codigo };
}

/** Fila de la búsqueda (§5.1). `conCoordenadas`: la respuesta las trae (alto rango, o "por error" para otros). */
export function filaBusqueda(h, { conCoordenadas = true } = {}) {
  const { latitud, longitud, ...resto } = h;
  return conCoordenadas
    ? { ...resto, estadoCoordenadas: 'VISIBLES', ubicacionProtegida: false, latitud, longitud }
    : { ...resto, estadoCoordenadas: 'PROTEGIDAS', ubicacionProtegida: true };
}

/** Feature GeoJSON (§5.2): coordinates = [longitud, latitud]. */
export function feature(h) {
  const { latitud, longitud, ...p } = h;
  return { type: 'Feature', id: h.id, geometry: { type: 'Point', coordinates: [longitud, latitud] }, properties: { hallazgoId: h.id, ...p } };
}

export function coleccion(hallazgos, extra = {}) {
  return {
    type: 'FeatureCollection',
    features: hallazgos.map(feature),
    total: hallazgos.length,
    devueltos: hallazgos.length,
    truncado: false,
    omitidos: 0,
    limite: 1000,
    ...extra,
  };
}

/**
 * Búsqueda paginada por defecto: `total` hallazgos generados a partir de los 3 base (las coordenadas
 * siempre vienen, para comprobar que la UI las oculta a los roles sin permiso aunque la API "se equivoque").
 */
export function paginaBusqueda(url, { total = 45 } = {}) {
  const page = Number(url.searchParams.get('page') ?? 0);
  const size = Number(url.searchParams.get('size') ?? 20);
  const desde = page * size;
  const filas = [];
  for (let i = desde; i < Math.min(total, desde + size); i += 1) {
    const base = HALLAZGOS[i % HALLAZGOS.length];
    filas.push(filaBusqueda(i < HALLAZGOS.length ? base : { ...base, id: `${base.id.slice(0, -4)}${String(i).padStart(4, '0')}` }));
  }
  return { content: filas, page, size, total, totalPaginas: Math.ceil(total / size), omitidos: 0 };
}

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Accept',
  'Access-Control-Allow-Methods': 'GET',
};

export const json = (status, cuerpo, headers = {}) => ({
  status,
  contentType: 'application/json',
  headers: { ...CORS, 'Cache-Control': 'no-store', ...headers },
  body: JSON.stringify(cuerpo),
});
export const sinCuerpo = (status, headers = {}) => ({ status, headers: { ...CORS, ...headers }, body: '' });
export const png = () => ({
  status: 200,
  contentType: 'image/png',
  headers: { ...CORS, 'Cache-Control': 'max-age=86400, private' },
  body: PNG_1X1,
});

/** Coordenadas de tesela a partir de la URL del gateway, o null. */
export function teselaDeUrl(url) {
  const m = new URL(url).pathname.match(/^\/api\/gateway\/teselas\/(\d+)\/(\d+)\/(\d+)\.png$/);
  return m ? { z: Number(m[1]), x: Number(m[2]), y: Number(m[3]) } : null;
}

/**
 * Intercepta el gateway. Cada manejador recibe la URL (objeto URL) y devuelve las opciones de
 * `route.fulfill`, o la cadena 'abortar' para simular una caída de red. Si devuelve una promesa, se espera.
 * Devuelve `api`: lista de { metodo, url, ruta, authorization } de cada petición al gateway.
 */
export async function simularGateway(page, { hallazgos, mapa, teselas } = {}) {
  const api = [];
  const manejadores = {
    hallazgos: hallazgos ?? ((url) => json(200, paginaBusqueda(url))),
    mapa: mapa ?? ((url) => {
      const id = url.searchParams.get('hallazgoId');
      return json(200, coleccion(id ? HALLAZGOS.filter((h) => h.id === id) : HALLAZGOS));
    }),
    teselas: teselas ?? (() => png()),
  };
  await page.route(`${API_URL}/api/**`, async (route) => {
    const peticion = route.request();
    const url = new URL(peticion.url());
    const authorization = await peticion.headerValue('authorization');
    api.push({ metodo: peticion.method(), url: url.href, ruta: url.pathname + url.search, authorization });
    if (peticion.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
    let tipo = null;
    if (url.pathname === '/api/cientifico/ubicacion/hallazgos') tipo = 'hallazgos';
    else if (url.pathname === '/api/cientifico/ubicacion/mapa') tipo = 'mapa';
    else if (url.pathname.startsWith('/api/gateway/teselas/')) tipo = 'teselas';
    if (!tipo) return route.fulfill(json(404, problema(404, 'NO_ENCONTRADO', 'No existe.', url.pathname)));
    const respuesta = await manejadores[tipo](url);
    if (respuesta === 'abortar') return route.abort('failed');
    return route.fulfill(respuesta);
  });
  return api;
}

/** Abre el arnés en modo simulado. */
export async function abrirArnes(page, { roles = [ROL_ALTO], pantalla = 'busqueda', hallazgo, token } = {}) {
  const p = new URLSearchParams({ modo: 'simulado', roles: roles.join(','), pantalla });
  if (hallazgo) p.set('hallazgo', hallazgo);
  if (token) p.set('token', token);
  await page.goto(`${RUTA_ARNES}?${p}`);
  await expect(page.getByRole('note', { name: 'Solo desarrollo' })).toBeVisible();
}

/**
 * Espía de `fetch` (se instala antes que cualquier script de la página). No cambia nada: registra la URL
 * y las opciones `cache` y `credentials` con que la app llama a fetch y delega en el original.
 * Es lo único observable igual en los tres motores para saber si una tesela se pidió con 'no-store' (D57),
 * porque Playwright desactiva la caché HTTP mientras hay rutas interceptadas.
 */
function instalarEspiaFetch() {
  const original = window.fetch.bind(window);
  window.__llamadasFetch = [];
  window.fetch = (entrada, init = {}) => {
    const url = typeof entrada === 'string' ? entrada : entrada?.url ?? String(entrada);
    window.__llamadasFetch.push({ url, cache: init.cache ?? null, credentials: init.credentials ?? null });
    return original(entrada, init);
  };
}

/**
 * Fixtures:
 *  - red: TODAS las peticiones de la página ({ url, metodo, tipo, cabeceras }) desde el evento `request`.
 *    Cualquier petición fuera de localhost se aborta (y queda registrada): las pruebas nunca salen a internet.
 *  - llamadasFetch(): lo que registró el espía de fetch.
 *  - errores: errores de página (pageerror) y console.error.
 *  - consola: todo lo que se escribió en la consola (WI-10).
 */
export const test = base.extend({
  red: async ({ page }, use) => {
    const red = [];
    page.on('request', (r) => {
      red.push({ url: r.url(), metodo: r.method(), tipo: r.resourceType(), cabeceras: r.headers(), peticion: r });
    });
    await page.route((url) => !['localhost', '127.0.0.1'].includes(url.hostname) && url.protocol.startsWith('http'), (route) => route.abort('blockedbyclient'));
    await use(red);
  },
  llamadasFetch: async ({ page }, use) => {
    await page.addInitScript(instalarEspiaFetch);
    await use(() => page.evaluate(() => window.__llamadasFetch ?? []));
  },
  consola: async ({ page }, use) => {
    const mensajes = [];
    page.on('console', (m) => mensajes.push(`${m.type()}: ${m.text()}`));
    await use(mensajes);
  },
  errores: async ({ page }, use) => {
    const errores = [];
    page.on('pageerror', (e) => errores.push(`pageerror: ${e.message}`));
    await use(errores);
  },
});
export { expect };
