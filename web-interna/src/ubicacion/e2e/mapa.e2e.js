import {
  abrirArnes,
  API_URL,
  coleccion,
  expect,
  HALLAZGOS,
  json,
  OTROS_ROLES,
  problema,
  ROL_ALTO,
  simularGateway,
  sinCuerpo,
  teselaDeUrl,
  test,
} from './fixtures.js';

const RUTA_MAPA = '/api/cientifico/ubicacion/mapa';
const pedidasMapa = (api) => api.filter((p) => p.metodo === 'GET' && p.ruta.startsWith(RUTA_MAPA));
const pedidasTeselas = (api) => api.filter((p) => p.metodo === 'GET' && teselaDeUrl(p.url));
const marcadores = (page) => page.locator('.leaflet-marker-icon');
const detalle = (page) => page.getByRole('complementary', { name: 'Detalle del hallazgo' });

test.describe('WI-05 · mapa con marcadores, teclado y panel de detalle (HU-07.2)', () => {
  test('WI-05: 3 marcadores; al pulsar uno se ve especie, fecha y coordenadas', async ({ page, errores }) => {
    await simularGateway(page);
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(page.getByRole('heading', { level: 1, name: 'Mapa de hallazgos' })).toBeVisible();
    await expect(page).toHaveTitle('Mapa de hallazgos · BioHub');
    await expect(page.getByText('3 hallazgos en el mapa')).toBeVisible();
    await expect(marcadores(page)).toHaveCount(3);
    await expect(page.locator('.leaflet-tile-loaded').first()).toBeAttached(); // el mapa base carga (PNG simulado)
    await expect(page.locator('.leaflet-control-attribution')).toContainText('OpenStreetMap');
    await expect(detalle(page)).toContainText('Selecciona un marcador para ver el detalle.');

    const [h] = HALLAZGOS;
    await page.getByRole('button', { name: `${h.nombreCientifico} (En peligro)` }).click();
    await expect(detalle(page).getByRole('heading', { level: 2 })).toHaveText(h.nombreCientifico);
    await expect(detalle(page)).toContainText('14/03/2026');
    await expect(detalle(page)).toContainText(h.latitud.toFixed(5));
    await expect(detalle(page)).toContainText(h.longitud.toFixed(5));
    await expect(detalle(page)).toContainText('Boyacá');
    expect(errores).toEqual([]);
  });

  test('WI-05: con teclado se llega a un marcador, Enter abre el detalle y Esc lo cierra devolviendo el foco', async ({ page }) => {
    await simularGateway(page);
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(marcadores(page)).toHaveCount(3);

    const [, h] = HALLAZGOS;
    const marcador = page.getByRole('button', { name: `${h.nombreCientifico} (No amenazada)` });
    await expect(marcador).toHaveAttribute('tabindex', '0');
    await marcador.focus();
    await expect(marcador).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(detalle(page).getByRole('heading', { level: 2 })).toHaveText(h.nombreCientifico);
    await page.keyboard.press('Escape');
    await expect(detalle(page)).toContainText('Selecciona un marcador para ver el detalle.');
    await expect(marcador).toBeFocused();
  });

  test('WI-05: "Saltar el mapa y ver la lista" y la vista Lista muestran las coordenadas en una tabla', async ({ page, errores }) => {
    await simularGateway(page);
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(marcadores(page)).toHaveCount(3);
    // Se pasa a Lista enseguida, con el ajuste animado de la vista (fitBounds) aún en curso: quitar el mapa
    // a mitad de una animación de zoom no debe dejar errores (temporizador de Leaflet 1.9 sobre un mapa ya quitado).
    await page.getByRole('link', { name: 'Saltar el mapa y ver la lista' }).click();
    const vista = page.getByRole('group', { name: 'Vista' });
    await expect(vista.getByRole('button', { name: 'Lista' })).toHaveAttribute('aria-pressed', 'true');
    const tabla = page.getByRole('table', { name: 'Hallazgos en el mapa' });
    await expect(tabla.locator('tbody tr')).toHaveCount(3);
    await expect(tabla).toContainText(HALLAZGOS[2].latitud.toFixed(5));
    await tabla.getByRole('button', { name: HALLAZGOS[2].nombreCientifico }).click();
    await expect(detalle(page).getByRole('heading', { level: 2 })).toHaveText(HALLAZGOS[2].nombreCientifico);
    await vista.getByRole('button', { name: 'Mapa' }).click();
    await expect(marcadores(page)).toHaveCount(3);
    await page.waitForTimeout(500); // deja vencer el temporizador de 250 ms de la animación de zoom
    expect(errores).toEqual([]);
  });

  test('WI-05: los filtros del mapa se envían a la API', async ({ page }) => {
    const api = await simularGateway(page);
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(marcadores(page)).toHaveCount(3);
    const filtros = page.getByRole('form', { name: 'Filtros del mapa' });
    await expect(filtros.getByLabel('Investigador')).toHaveCount(0);
    await filtros.getByLabel('Especie').fill('Atelopus');
    await filtros.getByLabel('Desde').fill('2026-01-01');
    await filtros.getByRole('button', { name: 'Aplicar filtros' }).click();
    await expect.poll(() => pedidasMapa(api).some((p) => p.ruta.includes('especie=Atelopus') && p.ruta.includes('desde=2026-01-01'))).toBe(true);
  });
});

test.describe('WI-06 · roles en el mapa: solo alto rango (HU-07.2, D21)', () => {
  for (const roles of [...OTROS_ROLES.map((r) => [r]), []]) {
    const nombre = roles[0] ?? 'sin rol';
    test(`WI-06: ${nombre} ve el acceso denegado y no se pide nada a la API`, async ({ page, red }) => {
      const api = await simularGateway(page);
      await abrirArnes(page, { roles, pantalla: 'mapa' });
      await expect(page.getByRole('heading', { level: 1, name: 'No tienes permiso para ver esta información' })).toBeFocused();
      await expect(page).toHaveTitle('Acceso denegado · BioHub');
      await expect(page.locator('.leaflet-container')).toHaveCount(0);
      expect(pedidasMapa(api)).toHaveLength(0);
      expect(pedidasTeselas(api)).toHaveLength(0);
      expect(red.filter((r) => r.url.startsWith(API_URL))).toHaveLength(0); // ni una petición al gateway

      await page.getByRole('button', { name: 'Ir a la búsqueda de hallazgos' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Búsqueda de hallazgos' })).toBeVisible();
    });
  }

  test('WC-03: alto rango con 403 de la API ve el acceso denegado y ningún marcador', async ({ page }) => {
    await simularGateway(page, {
      mapa: (url) => json(403, problema(403, 'ROL_INSUFICIENTE', 'No tienes permiso para ver esta información.', url.pathname)),
    });
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(page.getByRole('heading', { level: 1, name: 'No tienes permiso para ver esta información' })).toBeVisible();
    await expect(marcadores(page)).toHaveCount(0);
  });
});

test.describe('Estados del mapa (WI-07, WI-08, WI-12, WI-04)', () => {
  test('WI-07: sin hallazgos muestra el estado vacío', async ({ page }) => {
    await simularGateway(page, { mapa: () => json(200, coleccion([])) });
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(page.getByText('No hay hallazgos para los filtros seleccionados')).toBeVisible();
    await expect(page.getByText('0 hallazgos en el mapa')).toBeVisible();
    await expect(marcadores(page)).toHaveCount(0);
  });

  test('WI-08: respuesta truncada avisa que hay que afinar los filtros', async ({ page }) => {
    await simularGateway(page, { mapa: () => json(200, coleccion(HALLAZGOS, { total: 1500, truncado: true, limite: 3, omitidos: 2 })) });
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(page.getByText('3 hallazgos en el mapa (hay más)')).toBeVisible();
    await expect(page.getByText('Se muestran 3 de los hallazgos que cumplen los filtros. Aplica más filtros para ver el resto.')).toBeVisible();
    await expect(page.getByText('No se pudieron mostrar 2 hallazgos por un problema técnico.')).toBeVisible();
  });

  for (const caso of [
    { nombre: '500', respuesta: (url) => json(500, problema(500, 'ERROR_INTERNO', 'Error interno.', url.pathname)) },
    { nombre: '503', respuesta: (url) => json(503, problema(503, 'SERVICIO_NO_DISPONIBLE', 'No disponible.', url.pathname)) },
    { nombre: 'sin red', respuesta: () => 'abortar' },
  ]) {
    test(`WI-12: ${caso.nombre} en el mapa muestra mensaje con "Reintentar" y se recupera`, async ({ page }) => {
      let fallar = true;
      await simularGateway(page, { mapa: (url) => (fallar ? caso.respuesta(url) : json(200, coleccion(HALLAZGOS))) });
      await abrirArnes(page, { pantalla: 'mapa' });
      await expect(page.getByRole('alert')).toHaveText('No pudimos cargar el mapa. Intenta de nuevo.');
      await expect(marcadores(page)).toHaveCount(0);
      await expect(page.locator('body')).not.toContainText(/SERVICIO_NO_DISPONIBLE|ERROR_INTERNO|Exception/);
      fallar = false;
      await page.getByRole('button', { name: 'Reintentar' }).click();
      await expect(marcadores(page)).toHaveCount(3);
    });
  }

  test('WI-04: 401 del mapa pide iniciar sesión y no deja marcadores', async ({ page }) => {
    await simularGateway(page, { mapa: () => sinCuerpo(401, { 'WWW-Authenticate': 'Bearer' }) });
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(page.getByText('Tu sesión terminó. Te llevamos al inicio de sesión…')).toBeVisible();
    await expect(page.getByTestId('arnes-sesion-vencida')).toContainText('(1)');
    await expect(marcadores(page)).toHaveCount(0);
  });

  test('WC-12: 401 en las teselas pide iniciar sesión una sola vez', async ({ page }) => {
    await simularGateway(page, { teselas: () => sinCuerpo(401, { 'WWW-Authenticate': 'Bearer' }) });
    await abrirArnes(page, { pantalla: 'mapa' });
    await expect(page.getByTestId('arnes-sesion-vencida')).toBeVisible();
    await expect(page.getByTestId('arnes-sesion-vencida')).toContainText('(1)');
  });
});

test.describe('Mapa base caído (WI-15 429, WC-13)', () => {
  for (const caso of [
    { nombre: '429 (más de 600 teselas por minuto)', respuesta: () => json(429, problema(429, 'LIMITE_EXCEDIDO', 'Demasiadas peticiones.', '/api/gateway/teselas'), { 'Retry-After': '30' }) },
    { nombre: '503 (circuito abierto)', respuesta: () => json(503, problema(503, 'SERVICIO_NO_DISPONIBLE', 'No disponible.', '/api/gateway/teselas')) },
    { nombre: 'sin red', respuesta: () => 'abortar' },
  ]) {
    test(`teselas con ${caso.nombre}: aviso "No se pudo cargar el mapa base" y paso a la vista Lista`, async ({ page }) => {
      await simularGateway(page, { teselas: caso.respuesta });
      await abrirArnes(page, { pantalla: 'mapa' });
      await expect(page.getByText('No se pudo cargar el mapa base. Los hallazgos siguen disponibles en la vista Lista.')).toBeVisible();
      await expect(page.getByRole('group', { name: 'Vista' }).getByRole('button', { name: 'Lista' })).toHaveAttribute('aria-pressed', 'true');
      const tabla = page.getByRole('table', { name: 'Hallazgos en el mapa' });
      await expect(tabla.locator('tbody tr')).toHaveCount(3);
      await expect(page.getByText('No se pudo cargar el mapa base.')).toHaveCount(1); // un solo aviso
      await expect(page.getByTestId('arnes-sesion-vencida')).toHaveCount(0); // no es un cierre de sesión
    });
  }
});

test.describe('WI-14 · zoom máximo 12 (riesgo R6)', () => {
  test('WI-14: al acercar al máximo no se piden teselas de nivel mayor que 12', async ({ page }) => {
    const api = await simularGateway(page);
    await abrirArnes(page, { pantalla: 'mapa', hallazgo: HALLAZGOS[0].id });
    await expect(marcadores(page)).toHaveCount(1);
    const acercar = page.locator('.leaflet-control-zoom-in');
    for (let i = 0; i < 10 && !(await acercar.getAttribute('class')).includes('leaflet-disabled'); i += 1) {
      await acercar.click();
      await page.waitForTimeout(400); // animación de zoom de Leaflet
    }
    await expect(acercar).toHaveClass(/leaflet-disabled/);
    await expect.poll(() => pedidasTeselas(api).some((p) => teselaDeUrl(p.url).z === 12)).toBe(true);
    // Intentar seguir acercando con teclado y doble clic no cambia nada.
    await page.locator('.leaflet-container').focus();
    await page.keyboard.press('+');
    await page.locator('.leaflet-container').dblclick();
    await page.waitForTimeout(500);
    const niveles = pedidasTeselas(api).map((p) => teselaDeUrl(p.url).z);
    expect(Math.max(...niveles)).toBe(12);
  });
});
