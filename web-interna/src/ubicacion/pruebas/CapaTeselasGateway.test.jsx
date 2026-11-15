import { act, cleanup, render } from '@testing-library/react';
import { MapContainer } from 'react-leaflet';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { crearClienteUbicacion, ErrorApi } from '../api/ubicacionApi.js';
import { CapaTeselasGateway, RUTA_TESELAS, ZOOM_SIN_CACHE } from '../componentes/CapaTeselasGateway.js';
import TeselasGateway from '../componentes/TeselasGateway.jsx';

const PNG = () => new Blob(['png'], { type: 'image/png' });
const vaciar = () => new Promise((r) => setTimeout(r, 0));

/** Cliente falso cuyas peticiones se resuelven a mano; recuerda la señal de cada una. */
function clienteManual() {
  const peticiones = [];
  return {
    peticiones,
    getBlob: vi.fn((ruta, { signal }) =>
      new Promise((resolver, rechazar) => {
        const p = { ruta, signal, resolver, rechazar };
        signal.addEventListener('abort', () => rechazar(new DOMException('abortado', 'AbortError')));
        peticiones.push(p);
      })),
  };
}

let urls;
beforeEach(() => {
  urls = { creadas: [], revocadas: [] };
  let n = 0;
  URL.createObjectURL = vi.fn(() => {
    const u = `blob:prueba/${n++}`;
    urls.creadas.push(u);
    return u;
  });
  URL.revokeObjectURL = vi.fn((u) => urls.revocadas.push(u));
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('CapaTeselasGateway (capa Leaflet)', () => {
  it('SEC-J-24: desde el zoom 9 pide la tesela sin caché; por debajo, con caché', async () => {
    expect(ZOOM_SIN_CACHE).toBe(9);
    const cliente = clienteManual();
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente });
    for (const z of [0, 5, 8, 9, 10, 12]) capa.createTile({ x: 0, y: 0, z }, vi.fn());
    await vaciar();
    const sinCachePorZoom = cliente.getBlob.mock.calls.map(([ruta, op]) => [Number(ruta.split('/')[4]), op.sinCache]);
    expect(sinCachePorZoom).toEqual([[0, false], [5, false], [8, false], [9, true], [10, true], [12, true]]);
  });

  it('pide la tesela por el cliente con la ruta relativa del gateway y la muestra desde un object URL', async () => {
    const cliente = clienteManual();
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente });
    const listo = vi.fn();
    const img = capa.createTile({ x: 9, y: 15, z: 5 }, listo);
    await vaciar();
    expect(cliente.getBlob).toHaveBeenCalledTimes(1);
    expect(cliente.peticiones[0].ruta).toBe('/api/gateway/teselas/5/9/15.png');
    expect(cliente.peticiones[0].ruta).not.toMatch(/openstreetmap|^https?:/);
    expect(img.getAttribute('alt')).toBe('');
    expect(img.hasAttribute('src')).toBe(false); // nunca un src remoto

    await act(async () => cliente.peticiones[0].resolver(PNG()));
    expect(img.getAttribute('src')).toBe('blob:prueba/0');
    img.onload();
    expect(listo).toHaveBeenCalledWith(null, img);
    expect(urls.revocadas).toEqual(['blob:prueba/0']); // revocado al cargar
  });

  it('un error de la API llama a listo(error) y no deja nada pendiente', async () => {
    const cliente = { getBlob: vi.fn().mockRejectedValue(new ErrorApi(503, 'SERVICIO_NO_DISPONIBLE')) };
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente });
    const listo = vi.fn();
    const img = capa.createTile({ x: 1, y: 1, z: 3 }, listo);
    await vaciar();
    expect(listo).toHaveBeenCalledWith(expect.objectContaining({ status: 503 }), img);
    expect(capa._pendientes.size).toBe(0);
  });

  it('un cliente sin getBlob no rompe: el fallo llega como error de tesela', async () => {
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente: { get: vi.fn() } });
    const listo = vi.fn();
    capa.createTile({ x: 1, y: 1, z: 3 }, listo);
    await vaciar();
    expect(listo).toHaveBeenCalledWith(expect.any(TypeError), expect.anything());
  });

  it('descartar la tesela (tileunload) aborta la descarga y NO es un error', async () => {
    const cliente = clienteManual();
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente });
    const listo = vi.fn();
    const alErrorTesela = vi.fn();
    capa.on('tileerror', alErrorTesela);
    const img = capa.createTile({ x: 1, y: 1, z: 3 }, listo);
    await vaciar();
    capa.fire('tileunload', { tile: img });
    expect(cliente.peticiones[0].signal.aborted).toBe(true);
    await vaciar();
    expect(listo).not.toHaveBeenCalled();
    expect(alErrorTesela).not.toHaveBeenCalled();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    expect(capa._pendientes.size).toBe(0);
  });

  it('si la respuesta llega después de abortar, se descarta el blob sin crear object URL', async () => {
    const capa = new CapaTeselasGateway(RUTA_TESELAS, {
      cliente: { getBlob: (_r, { signal }) => new Promise((res) => setTimeout(() => res(PNG()), 5, signal)) },
    });
    const listo = vi.fn();
    const img = capa.createTile({ x: 1, y: 1, z: 3 }, listo);
    capa.fire('tileabort', { tile: img });
    await new Promise((r) => setTimeout(r, 15));
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    expect(listo).not.toHaveBeenCalled();
  });

  it('abortar con el object URL ya creado (decodificando) lo revoca', async () => {
    const cliente = clienteManual();
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente });
    const img = capa.createTile({ x: 1, y: 1, z: 3 }, vi.fn());
    await vaciar();
    await act(async () => cliente.peticiones[0].resolver(PNG()));
    expect(urls.creadas).toEqual(['blob:prueba/0']);
    capa.fire('tileunload', { tile: img });
    expect(urls.revocadas).toEqual(['blob:prueba/0']);
    capa.fire('tileunload', { tile: img }); // idempotente
    expect(urls.revocadas).toHaveLength(1);
  });

  it('si la imagen no se puede decodificar: revoca y reporta error', async () => {
    const cliente = clienteManual();
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente });
    const listo = vi.fn();
    const img = capa.createTile({ x: 1, y: 1, z: 3 }, listo);
    await vaciar();
    await act(async () => cliente.peticiones[0].resolver(PNG()));
    img.onerror();
    expect(urls.revocadas).toEqual(['blob:prueba/0']);
    expect(listo).toHaveBeenCalledWith(expect.any(Error), img);
  });

  it('al cambiar de zoom (_abortLoading) aborta solo las descargas del nivel anterior y avisa tileabort', async () => {
    const cliente = clienteManual();
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente });
    const viejo = capa.createTile({ x: 1, y: 1, z: 5 }, vi.fn());
    const actual = capa.createTile({ x: 2, y: 2, z: 6 }, vi.fn());
    await vaciar();
    capa._tiles = { '1:1:5': { el: viejo, coords: { x: 1, y: 1, z: 5 } }, '2:2:6': { el: actual, coords: { x: 2, y: 2, z: 6 } } };
    capa._tileZoom = 6;
    const alAbortar = vi.fn();
    capa.on('tileabort', alAbortar);
    capa._abortLoading();
    expect(cliente.peticiones.map((p) => p.signal.aborted)).toEqual([true, false]);
    expect(alAbortar).toHaveBeenCalledTimes(1);
    expect(Object.keys(capa._tiles)).toEqual(['2:2:6']);
  });

  it('un 401 llama una sola vez a alSesionVencida y cada tesela sigue reportando su error', async () => {
    const alSesionVencida = vi.fn();
    const cliente = { getBlob: vi.fn().mockRejectedValue(new ErrorApi(401)) };
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente, alSesionVencida });
    const listo = vi.fn();
    for (let i = 0; i < 5; i++) capa.createTile({ x: i, y: 0, z: 4 }, listo);
    await vaciar();
    expect(alSesionVencida).toHaveBeenCalledTimes(1);
    expect(listo).toHaveBeenCalledTimes(5);
  });

  it('otros estados (403, 429, 5xx, sin red) no cierran la sesión', async () => {
    const alSesionVencida = vi.fn();
    for (const status of [0, 403, 429, 502]) {
      const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente: { getBlob: vi.fn().mockRejectedValue(new ErrorApi(status)) }, alSesionVencida });
      capa.createTile({ x: 0, y: 0, z: 4 }, vi.fn());
    }
    await vaciar();
    expect(alSesionVencida).not.toHaveBeenCalled();
  });
});

describe('TeselasGateway dentro de un mapa Leaflet real (jsdom)', () => {
  beforeEach(() => {
    // jsdom no calcula tamaños: sin esto Leaflet cree que el mapa mide 0x0 y no pide teselas.
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(512);
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(512);
  });
  afterEach(() => vi.restoreAllMocks());

  function montar(cliente, props = {}) {
    let mapa;
    const r = render(
      <MapContainer center={[4.5, -74.1]} zoom={5} maxZoom={12} ref={(m) => { if (m) mapa = m; }} style={{ height: 512, width: 512 }}>
        <TeselasGateway cliente={cliente} attribution="OSM" maxZoom={12} {...props} />
      </MapContainer>,
    );
    return { ...r, obtenerMapa: () => mapa };
  }

  it('todas las teselas pedidas van a /api/gateway/teselas/... por el cliente y ninguna a openstreetmap', async () => {
    const f = vi.fn();
    vi.stubGlobal('fetch', f);
    const cliente = clienteManual();
    montar(cliente);
    await vaciar();
    expect(cliente.getBlob.mock.calls.length).toBeGreaterThan(0);
    for (const [ruta] of cliente.getBlob.mock.calls) expect(ruta).toMatch(/^\/api\/gateway\/teselas\/5\/\d+\/\d+\.png$/);
    expect(document.querySelectorAll('img[src*="openstreetmap"]')).toHaveLength(0);
    expect(f).not.toHaveBeenCalled();
  });

  it('con el cliente real: fetch al gateway con Authorization Bearer y nunca a openstreetmap', async () => {
    const f = vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(PNG()) });
    vi.stubGlobal('fetch', f);
    const cliente = crearClienteUbicacion({ apiUrl: 'http://gw.test', obtenerToken: async () => 'JWT-1' });
    montar(cliente);
    await act(vaciar);
    await act(vaciar);
    expect(f.mock.calls.length).toBeGreaterThan(0);
    for (const [url, op] of f.mock.calls) {
      expect(url).toMatch(/^http:\/\/gw\.test\/api\/gateway\/teselas\/5\/\d+\/\d+\.png$/);
      expect(url).not.toMatch(/openstreetmap/);
      expect(op.headers.Authorization).toBe('Bearer JWT-1');
    }
  });

  it('un error de tesela llega a alErrorTesela; las abortadas no', async () => {
    const alErrorTesela = vi.fn();
    const cliente = { getBlob: vi.fn().mockRejectedValue(new ErrorApi(502)) };
    montar(cliente, { alErrorTesela });
    await act(vaciar);
    const pedidas = cliente.getBlob.mock.calls.length;
    expect(pedidas).toBeGreaterThan(0);
    expect(alErrorTesela).toHaveBeenCalledTimes(pedidas);

    const alErrorAbort = vi.fn();
    const manual = clienteManual();
    const { unmount } = montar(manual, { alErrorTesela: alErrorAbort });
    await act(vaciar);
    unmount(); // quitar el mapa descarta todas las teselas
    await act(vaciar);
    expect(manual.peticiones.length).toBeGreaterThan(0);
    expect(manual.peticiones.every((p) => p.signal.aborted)).toBe(true);
    expect(alErrorAbort).not.toHaveBeenCalled();
  });

  it('al hacer zoom se abortan las descargas pendientes del nivel anterior', async () => {
    const cliente = clienteManual();
    const { obtenerMapa } = montar(cliente);
    await act(vaciar);
    const delNivel5 = [...cliente.peticiones];
    expect(delNivel5.length).toBeGreaterThan(0);
    act(() => obtenerMapa().setView([4.5, -74.1], 7, { animate: false }));
    await act(vaciar);
    expect(delNivel5.every((p) => p.signal.aborted)).toBe(true);
    expect(cliente.peticiones.length).toBeGreaterThan(delNivel5.length);
    const nuevas = cliente.peticiones.slice(delNivel5.length);
    expect(nuevas.some((p) => !p.signal.aborted)).toBe(true);
  });

  it('un 401 de tesela llama una sola vez a alSesionVencida', async () => {
    const alSesionVencida = vi.fn();
    const cliente = { getBlob: vi.fn().mockRejectedValue(new ErrorApi(401)) };
    montar(cliente, { alSesionVencida });
    await act(vaciar);
    expect(cliente.getBlob.mock.calls.length).toBeGreaterThan(1);
    expect(alSesionVencida).toHaveBeenCalledTimes(1);
  });
});
