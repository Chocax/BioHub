import { abrirArnes, API_URL, CENTINELAS, expect, HALLAZGOS, simularGateway, teselaDeUrl, test, TOKEN_SIMULADO } from './fixtures.js';

const ZOOM_SIN_CACHE = 9; // el mismo de componentes/CapaTeselasGateway.js (D57)
const esOsm = (url) => /(^|\.)openstreetmap\.org$/i.test(new URL(url).hostname);
const esHttp = (url) => /^https?:/.test(url);

/** Acerca el mapa con el control "+" hasta el tope (12) esperando la animación de cada paso. */
async function acercarAlMaximo(page) {
  const acercar = page.locator('.leaflet-control-zoom-in');
  for (let i = 0; i < 10 && !(await acercar.getAttribute('class')).includes('leaflet-disabled'); i += 1) {
    await acercar.click();
    await page.waitForTimeout(400);
  }
  await expect(acercar).toHaveClass(/leaflet-disabled/);
}

test.describe('WI-15 / SEC-J-04 / SEC-J-24 · teselas por el gateway (D56, D57)', () => {
  test('WI-15: ninguna petición a OpenStreetMap; cada tesela va al gateway con Bearer; zoom ≥ 9 con cache "no-store"', async ({
    page,
    red,
    llamadasFetch,
    browserName,
  }) => {
    const api = await simularGateway(page);
    // Un solo hallazgo: la vista se ajusta a zoom 8 (ZOOM_AJUSTE_UN_PUNTO) y luego se acerca hasta 12.
    await abrirArnes(page, { pantalla: 'mapa', hallazgo: HALLAZGOS[0].id });
    await expect(page.locator('.leaflet-marker-icon')).toHaveCount(1);
    await expect(page.locator('.leaflet-tile-loaded').first()).toBeAttached();
    await acercarAlMaximo(page);
    await expect.poll(() => api.some((p) => teselaDeUrl(p.url)?.z === 12)).toBe(true);
    await expect(page.locator('.leaflet-tile-loaded').first()).toBeAttached();

    // 1) TODAS las peticiones de la página: ninguna a *.openstreetmap.org ni fuera de localhost.
    const http = red.filter((r) => esHttp(r.url));
    expect(http.length).toBeGreaterThan(0);
    expect(http.filter((r) => esOsm(r.url)).map((r) => r.url)).toEqual([]);
    expect(http.filter((r) => !['localhost', '127.0.0.1'].includes(new URL(r.url).hostname)).map((r) => r.url)).toEqual([]);

    // 2) Cada tesela va a {apiUrl}/api/gateway/teselas/{z}/{x}/{y}.png con Authorization: Bearer y z ≤ 12.
    const teselasRed = http.filter((r) => new URL(r.url).pathname.includes('/teselas/'));
    expect(teselasRed.length).toBeGreaterThan(0);
    for (const r of teselasRed) {
      expect(new URL(r.url).origin).toBe(API_URL);
      expect(teselaDeUrl(r.url), r.url).not.toBeNull();
      expect(r.tipo, 'la tesela se pide con fetch, no con <img src>').not.toBe('image');
    }
    const teselasApi = api.filter((p) => p.metodo === 'GET' && teselaDeUrl(p.url));
    for (const p of teselasApi) expect(p.authorization).toBe(`Bearer ${TOKEN_SIMULADO}`);
    expect(Math.max(...teselasApi.map((p) => teselaDeUrl(p.url).z))).toBe(12);

    // Las imágenes del mapa se pintan desde object URLs (blob:), nunca desde un host de teselas.
    const fuentes = await page.locator('img.leaflet-tile').evaluateAll((imgs) => imgs.map((i) => i.getAttribute('src') ?? ''));
    expect(fuentes.filter((s) => s && !s.startsWith('blob:') && !s.startsWith('data:'))).toEqual([]);

    // 3) D57: opciones con que la app llamó a fetch (espía en la página; igual en los tres motores).
    const llamadas = (await llamadasFetch()).filter((l) => teselaDeUrl(l.url));
    const bajas = llamadas.filter((l) => teselaDeUrl(l.url).z < ZOOM_SIN_CACHE);
    const altas = llamadas.filter((l) => teselaDeUrl(l.url).z >= ZOOM_SIN_CACHE);
    expect(bajas.length, 'hubo teselas de zoom ≤ 8').toBeGreaterThan(0);
    expect(altas.length, 'hubo teselas de zoom ≥ 9').toBeGreaterThan(0);
    for (const l of altas) expect(l.cache, `${l.url} debe pedirse sin caché`).toBe('no-store');
    for (const l of bajas) expect(l.cache, `${l.url} debe poder usar la caché`).toBeNull();
    for (const l of llamadas) expect(l.credentials).toBe('omit');

    // 4) Cabeceras de caché a nivel de red: solo se documentan. Con page.route activo Playwright desactiva la
    //    caché HTTP; Chromium y Firefox no exponen Cache-Control/Pragma en estas peticiones, y WebKit pone
    //    "no-cache" en TODAS (también en las de zoom ≤ 8). En ningún motor distinguen el zoom: la prueba
    //    real de que no quedan en disco es manual (README, guía del modo real).
    const vistas = new Set();
    for (const r of teselasRed) {
      const cab = await r.peticion.allHeaders().catch(() => r.cabeceras);
      const z = teselaDeUrl(r.url).z;
      vistas.add(`${z >= ZOOM_SIN_CACHE ? 'z>=9' : 'z<=8'} cache-control=${cab['cache-control'] ?? '-'} pragma=${cab.pragma ?? '-'}`);
    }
    test.info().annotations.push({ type: `cabeceras de caché observadas (${browserName})`, description: [...vistas].sort().join(' | ') });
  });

  test('WI-15: el aviso del 429 no hace pedir teselas a OpenStreetMap como respaldo', async ({ page, red }) => {
    await simularGateway(page, { teselas: () => ({ status: 429, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*', 'Retry-After': '30' }, body: '{"status":429,"codigo":"LIMITE_EXCEDIDO"}' }) });
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(page.getByText('No se pudo cargar el mapa base. Los hallazgos siguen disponibles en la vista Lista.')).toBeVisible();
    expect(red.filter((r) => esHttp(r.url) && esOsm(r.url))).toEqual([]);
  });
});

test.describe('WI-10 · coordenadas fuera de almacenamiento, URL, título y consola (03 J2)', () => {
  test('WI-10: recorrer búsqueda, mapa, detalle y lista no deja coordenadas fuera del DOM', async ({ page, consola, context }) => {
    await simularGateway(page);
    await abrirArnes(page);
    await expect(page.getByRole('cell', { name: '5.12346, -73.12346' }).first()).toBeVisible();
    await page.getByRole('button', { name: `Ver en el mapa: ${HALLAZGOS[0].nombreCientifico}` }).first().click();
    await expect(page.getByRole('complementary', { name: 'Detalle del hallazgo' })).toContainText('5.12346');
    await page.getByRole('button', { name: 'Ver todos los hallazgos' }).click();
    await expect(page.locator('.leaflet-marker-icon')).toHaveCount(3);
    await page.getByRole('group', { name: 'Vista' }).getByRole('button', { name: 'Lista' }).click();
    await expect(page.getByRole('table', { name: 'Hallazgos en el mapa' })).toContainText('5.69123');

    const fuera = await page.evaluate(() => ({
      url: window.location.href,
      titulo: document.title,
      local: JSON.stringify({ ...window.localStorage }),
      sesion: JSON.stringify({ ...window.sessionStorage }),
      cookies: document.cookie,
    }));
    const cookies = JSON.stringify(await context.cookies());
    const textos = { ...fuera, cookies, consola: consola.join('\n') };
    for (const [donde, texto] of Object.entries(textos)) {
      for (const c of CENTINELAS) expect(texto, `${donde} no debe contener ${c}`).not.toContain(c);
    }

    // Al salir del mapa las coordenadas desaparecen del DOM (el estado se descarta al desmontar).
    await page.getByRole('navigation', { name: 'Pantallas del módulo de ubicación' }).getByRole('button', { name: 'Búsqueda' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Búsqueda de hallazgos' })).toBeVisible();
    await expect(page.locator('.leaflet-container')).toHaveCount(0);
  });
});
