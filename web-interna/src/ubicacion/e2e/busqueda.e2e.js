import {
  abrirArnes,
  CENTINELAS,
  expect,
  HALLAZGOS,
  json,
  OTROS_ROLES,
  paginaBusqueda,
  problema,
  ROL_ALTO,
  simularGateway,
  sinCuerpo,
  test,
} from './fixtures.js';

const RUTA_HALLAZGOS = '/api/cientifico/ubicacion/hallazgos';
const pedidasBusqueda = (api) => api.filter((p) => p.metodo === 'GET' && p.ruta.startsWith(RUTA_HALLAZGOS));
const ultimaBusqueda = (api) => new URL(pedidasBusqueda(api).at(-1).url).searchParams;
const TEXTO_ROL_RESTRINGIDO = /Tu rol no permite ver las coordenadas exactas/;

async function expectSinCentinelas(page) {
  const html = await page.content();
  for (const c of CENTINELAS) expect(html, `el DOM no debe contener ${c}`).not.toContain(c);
}

test.describe('WI-01 · búsqueda con alto rango: filtros y paginación (HU-13.3)', () => {
  test('WI-01: muestra coordenadas, envía los filtros y pagina', async ({ page, errores }) => {
    const api = await simularGateway(page);
    await abrirArnes(page, { roles: [ROL_ALTO] });

    await expect(page.getByRole('heading', { level: 1, name: 'Búsqueda de hallazgos' })).toBeVisible();
    await expect(page.getByText('45 hallazgos')).toBeVisible();
    await expect(page).toHaveTitle('Búsqueda de hallazgos · BioHub');
    await expect(page.getByRole('cell', { name: '5.12346, -73.12346' }).first()).toBeVisible();
    await expect(page.getByText('Restringido')).toHaveCount(0);
    expect(ultimaBusqueda(api).get('page')).toBe('0');
    expect(ultimaBusqueda(api).get('size')).toBe('20');
    for (const p of pedidasBusqueda(api)) expect(p.authorization).toBe('Bearer token-simulado-e2e');

    await page.getByLabel('Especie').fill('Atelopus');
    await page.getByLabel('Desde').fill('2026-01-01');
    await page.getByLabel('Hasta').fill('2026-06-30');
    await page.getByLabel('Departamento').selectOption({ label: 'Boyacá' });
    await page.getByRole('button', { name: 'Buscar' }).click();
    await expect(page.getByText('45 hallazgos')).toBeFocused(); // el foco pasa al resumen (02 §9)
    let q = ultimaBusqueda(api);
    expect(q.get('especie')).toBe('Atelopus');
    expect(q.get('desde')).toBe('2026-01-01');
    expect(q.get('hasta')).toBe('2026-06-30');
    expect(q.get('departamento')).toBe('15');
    expect(q.get('page')).toBe('0');

    const paginacion = page.getByRole('navigation', { name: 'Paginación' });
    await expect(paginacion).toContainText('Página 1 de 3');
    await paginacion.getByRole('button', { name: 'Siguiente' }).click();
    await expect(paginacion).toContainText('Página 2 de 3');
    q = ultimaBusqueda(api);
    expect(q.get('page')).toBe('1');
    expect(q.get('especie')).toBe('Atelopus'); // la paginación conserva los filtros
    await paginacion.getByRole('button', { name: 'Anterior' }).click();
    await expect(paginacion).toContainText('Página 1 de 3');

    await page.getByLabel('Mostrar').selectOption('50');
    await expect(page.getByRole('navigation', { name: 'Paginación' })).toHaveCount(0); // 45 caben en 1 página
    q = ultimaBusqueda(api);
    expect(q.get('size')).toBe('50');
    expect(q.get('page')).toBe('0');
    expect(errores).toEqual([]);
  });

  test('WC-07: "Desde" posterior a "Hasta" se valida en el cliente sin llamar a la API', async ({ page }) => {
    const api = await simularGateway(page);
    await abrirArnes(page);
    await expect(page.getByText('45 hallazgos')).toBeVisible();
    const antes = pedidasBusqueda(api).length;
    await page.getByLabel('Desde').fill('2026-06-30');
    await page.getByLabel('Hasta').fill('2026-01-01');
    await page.getByRole('button', { name: 'Buscar' }).click();
    await expect(page.getByText('La fecha «Desde» no puede ser posterior a la fecha «Hasta».')).toBeVisible();
    await expect(page.getByLabel('Hasta')).toBeFocused();
    await expect(page.getByLabel('Hasta')).toHaveAttribute('aria-invalid', 'true');
    await page.getByLabel('Especie').fill('A');
    await page.getByRole('button', { name: 'Buscar' }).click();
    await expect(page.getByText('Escribe al menos 2 letras.')).toBeVisible();
    expect(pedidasBusqueda(api).length).toBe(antes);
  });
});

test.describe('WI-02 · búsqueda sin permiso de coordenadas (HU-13.3, D21)', () => {
  for (const rol of OTROS_ROLES) {
    test(`WI-02: ${rol} ve "Restringido" aunque la API envíe coordenadas por error`, async ({ page }) => {
      await simularGateway(page); // la respuesta simulada SIEMPRE trae latitud/longitud
      await abrirArnes(page, { roles: [rol] });
      await expect(page.getByText('45 hallazgos')).toBeVisible();
      await expect(page.getByText(TEXTO_ROL_RESTRINGIDO).first()).toBeVisible();
      await expect(page.locator('tbody tr')).toHaveCount(20);
      await expect(page.locator('tbody').getByText('Restringido')).toHaveCount(20);
      await expect(page.getByRole('button', { name: /Ver en el mapa/ })).toHaveCount(0);
      await expect(page.getByRole('columnheader', { name: /Acción/ })).toHaveCount(0);
      await expectSinCentinelas(page);
    });
  }

  test('WI-02: usuario sin ningún rol de BioHub recibe 403 y ve el acceso denegado', async ({ page }) => {
    await simularGateway(page, {
      hallazgos: (url) => json(403, problema(403, 'ROL_INSUFICIENTE', 'No tienes permiso para ver esta información.', url.pathname)),
    });
    await abrirArnes(page, { roles: [] });
    await expect(page.getByTestId('arnes-roles')).toHaveText('ninguno');
    await expect(page.getByRole('heading', { level: 1, name: 'No tienes permiso para ver esta información' })).toBeFocused();
    await expect(page).toHaveTitle('Acceso denegado · BioHub');
  });
});

test.describe('WI-03 · sin resultados', () => {
  test('WI-03: lista vacía muestra el estado vacío y permite limpiar los filtros', async ({ page }) => {
    const api = await simularGateway(page, {
      hallazgos: (url) => json(200, url.searchParams.get('especie') ? paginaBusqueda(url, { total: 0 }) : paginaBusqueda(url)),
    });
    await abrirArnes(page);
    await expect(page.getByText('45 hallazgos')).toBeVisible();
    await page.getByLabel('Especie').fill('Inexistente');
    await page.getByRole('button', { name: 'Buscar' }).click();
    await expect(page.getByText('No hay hallazgos que coincidan con los filtros')).toBeVisible();
    await expect(page.locator('table')).toHaveCount(0);
    await page.getByRole('status').getByRole('button', { name: 'Limpiar filtros' }).click();
    await expect(page.getByText('45 hallazgos')).toBeVisible();
    expect(ultimaBusqueda(api).has('especie')).toBe(false);
  });
});

test.describe('WI-04 · sesión vencida', () => {
  test('WI-04: un 401 pide iniciar sesión y quita los datos que había en pantalla', async ({ page }) => {
    await simularGateway(page, {
      hallazgos: (url) =>
        url.searchParams.get('especie')
          ? sinCuerpo(401, { 'WWW-Authenticate': 'Bearer' })
          : json(200, paginaBusqueda(url)),
    });
    await abrirArnes(page);
    await expect(page.getByRole('cell', { name: '5.12346, -73.12346' }).first()).toBeVisible();
    await page.getByLabel('Especie').fill('Atelopus');
    await page.getByRole('button', { name: 'Buscar' }).click();
    await expect(page.getByText('Tu sesión terminó. Te llevamos al inicio de sesión…')).toBeVisible();
    await expect(page.getByTestId('arnes-sesion-vencida')).toContainText('(1)');
    await expect(page.locator('table')).toHaveCount(0);
    await expectSinCentinelas(page);
  });

  test('WI-04: si no se puede renovar el token, no se llama a la API y se pide iniciar sesión', async ({ page }) => {
    const api = await simularGateway(page);
    await abrirArnes(page, { token: 'vencido' });
    await expect(page.getByText('Tu sesión terminó. Te llevamos al inicio de sesión…')).toBeVisible();
    await expect(page.getByTestId('arnes-sesion-vencida')).toBeVisible();
    expect(pedidasBusqueda(api)).toHaveLength(0);
  });
});

test.describe('WI-12 · errores del servidor y de red en la búsqueda', () => {
  for (const caso of [
    { nombre: '500', respuesta: (url) => json(500, problema(500, 'ERROR_INTERNO', 'Error interno.', url.pathname)) },
    { nombre: '503', respuesta: (url) => json(503, problema(503, 'SERVICIO_NO_DISPONIBLE', 'No disponible.', url.pathname), { 'Retry-After': '5' }) },
    { nombre: 'sin red', respuesta: () => 'abortar' },
  ]) {
    test(`WI-12: ${caso.nombre} muestra un mensaje en español y "Reintentar" recupera la búsqueda`, async ({ page }) => {
      let fallar = true;
      await simularGateway(page, { hallazgos: (url) => (fallar ? caso.respuesta(url) : json(200, paginaBusqueda(url))) });
      await abrirArnes(page);
      await expect(page.getByRole('alert')).toHaveText('No pudimos cargar los hallazgos. Intenta de nuevo.');
      await expect(page.locator('body')).not.toContainText(/SERVICIO_NO_DISPONIBLE|ERROR_INTERNO|Exception|\b50[03]\b/);
      fallar = false;
      await page.getByRole('button', { name: 'Reintentar' }).click();
      await expect(page.getByText('45 hallazgos')).toBeVisible();
      await expect(page.getByRole('alert')).toHaveCount(0);
    });
  }

  test('WI-12: un 400 del servidor pide revisar los filtros (sin "Reintentar")', async ({ page }) => {
    await simularGateway(page, { hallazgos: (url) => json(400, problema(400, 'PARAMETRO_INVALIDO', 'Revisa los parámetros.', url.pathname)) });
    await abrirArnes(page);
    await expect(page.getByRole('alert')).toHaveText('Algo en la búsqueda no es válido. Revisa los filtros.');
    await expect(page.getByRole('button', { name: 'Reintentar' })).toHaveCount(0);
  });
});

test.describe('WI-09 · de la búsqueda al mapa (F7)', () => {
  test('WI-09: "Ver en el mapa" abre el mapa con ese hallazgo, centrado y con el detalle abierto', async ({ page }) => {
    const api = await simularGateway(page);
    await abrirArnes(page);
    const [h] = HALLAZGOS;
    await page.getByRole('button', { name: `Ver en el mapa: ${h.nombreCientifico}` }).first().click();

    await expect(page.getByRole('heading', { level: 1, name: 'Mapa de hallazgos' })).toBeVisible();
    await expect(page.getByText('Estás viendo solo el hallazgo elegido.')).toBeVisible();
    await expect(page.locator('.leaflet-marker-icon')).toHaveCount(1);
    const detalle = page.getByRole('complementary', { name: 'Detalle del hallazgo' });
    await expect(detalle).toContainText(h.nombreCientifico);
    await expect(detalle).toContainText(h.latitud.toFixed(5));
    const mapa = api.filter((p) => p.ruta.startsWith('/api/cientifico/ubicacion/mapa')).at(-1);
    expect(new URL(mapa.url).searchParams.get('hallazgoId')).toBe(h.id);
    expect(page.url()).not.toContain(h.id); // el arnés no pone el id ni las coordenadas en la URL

    await page.getByRole('button', { name: 'Ver todos los hallazgos' }).click();
    await expect(page.locator('.leaflet-marker-icon')).toHaveCount(3);
  });
});
