// QA (iteración 20b): huecos de cobertura de las teselas por el gateway. No sustituye a CapaTeselasGateway.test.jsx.
import { act, cleanup, render } from '@testing-library/react';
import { StrictMode } from 'react';
import { MapContainer } from 'react-leaflet';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { crearClienteUbicacion, ErrorApi } from '../api/ubicacionApi.js';
import { CapaTeselasGateway, RUTA_TESELAS } from '../componentes/CapaTeselasGateway.js';
import MapaHallazgos, { MAX_ERRORES_TESELAS } from '../componentes/MapaHallazgos.jsx';
import TeselasGateway from '../componentes/TeselasGateway.jsx';

const PNG = () => new Blob(['png'], { type: 'image/png' });
const vaciar = () => new Promise((r) => setTimeout(r, 0));

/** Cliente que IGNORA la señal (peor caso): resuelve/rechaza cuando el test quiere. */
function clienteSordo() {
  const p = [];
  return {
    p,
    getBlob: vi.fn(() => new Promise((resolver, rechazar) => p.push({ resolver, rechazar }))),
  };
}

beforeEach(() => {
  let n = 0;
  URL.createObjectURL = vi.fn(() => `blob:qa/${n++}`);
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(512);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(512);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const mapaConTeselas = (cliente, props = {}, envolver = (x) => x) =>
  render(envolver(
    <MapContainer center={[4.5, -74.1]} zoom={5} maxZoom={12} style={{ height: 512, width: 512 }}>
      <TeselasGateway cliente={cliente} attribution="OSM" maxZoom={12} {...props} />
    </MapContainer>,
  ));

describe('QA teselas: respuestas tardías tras desmontar el mapa', () => {
  it('un blob que llega tras desmontar no crea object URL ni llama a nada', async () => {
    const cliente = clienteSordo();
    const alErrorTesela = vi.fn();
    const { unmount } = mapaConTeselas(cliente, { alErrorTesela });
    await act(vaciar);
    const n = cliente.p.length;
    expect(n).toBeGreaterThan(0);
    unmount();
    await act(async () => cliente.p.forEach((x) => x.resolver(PNG())));
    await act(vaciar);
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    expect(alErrorTesela).not.toHaveBeenCalled();
  });

  it('un 401 que llega tras desmontar NO cierra la sesión', async () => {
    const cliente = clienteSordo();
    const alSesionVencida = vi.fn();
    const { unmount } = mapaConTeselas(cliente, { alSesionVencida });
    await act(vaciar);
    unmount();
    await act(async () => cliente.p.forEach((x) => x.rechazar(new ErrorApi(401))));
    await act(vaciar);
    expect(alSesionVencida).not.toHaveBeenCalled();
  });
});

describe('QA teselas: StrictMode y cambio de cliente', () => {
  it('StrictMode (doble montaje): sin teselas huérfanas ni errores; las del primer montaje quedan abortadas', async () => {
    const señales = [];
    const cliente = { getBlob: vi.fn((_r, { signal }) => new Promise(() => señales.push(signal))) };
    const alErrorTesela = vi.fn();
    mapaConTeselas(cliente, { alErrorTesela }, (x) => <StrictMode>{x}</StrictMode>);
    await act(vaciar);
    expect(alErrorTesela).not.toHaveBeenCalled();
    expect(señales.some((s) => s.aborted)).toBe(true); // 1.er montaje descartado
    expect(señales.some((s) => !s.aborted)).toBe(true); // 2.º montaje vivo
    expect(document.querySelectorAll('.leaflet-tile-pane .leaflet-layer')).toHaveLength(1); // una sola capa
  });

  it('cambiar de cliente entre renders recrea la capa y aborta las descargas del cliente anterior', async () => {
    const a = clienteSordo();
    const b = clienteSordo();
    const ui = (c) => (
      <MapContainer center={[4.5, -74.1]} zoom={5} maxZoom={12} style={{ height: 512, width: 512 }}>
        <TeselasGateway cliente={c} attribution="OSM" maxZoom={12} />
      </MapContainer>
    );
    const { rerender } = render(ui(a));
    await act(vaciar);
    expect(a.getBlob).toHaveBeenCalled();
    rerender(ui(b));
    await act(vaciar);
    expect(b.getBlob).toHaveBeenCalled();
    expect(document.querySelectorAll('.leaflet-tile-pane .leaflet-layer')).toHaveLength(1);
    // el cliente A ya no recibe señales vivas
    const señalesA = a.getBlob.mock.calls.map((c) => c[1].signal);
    expect(señalesA.every((s) => s.aborted)).toBe(true);
  });

  it('cambio de cliente (p. ej. token renovado) con 401 persistente llama otra vez a alSesionVencida (observación: una vez POR CAPA)', async () => {
    const alSesionVencida = vi.fn();
    const mk = () => ({ getBlob: vi.fn().mockRejectedValue(new ErrorApi(401)) });
    const ui = (c) => (
      <MapContainer center={[4.5, -74.1]} zoom={5} maxZoom={12} style={{ height: 512, width: 512 }}>
        <TeselasGateway cliente={c} attribution="OSM" maxZoom={12} alSesionVencida={alSesionVencida} />
      </MapContainer>
    );
    const { rerender } = render(ui(mk()));
    await act(vaciar);
    rerender(ui(mk()));
    await act(vaciar);
    // Documenta el comportamiento actual: 2 (una por capa). Si se endurece a "una vez por montaje", cambiar a 1.
    expect(alSesionVencida).toHaveBeenCalledTimes(2);
  });
});

describe('QA teselas: umbral del aviso del mapa base (MapaHallazgos con Leaflet real)', () => {
  const montar = (cliente, onTeselasFallidas) =>
    render(<MapaHallazgos cliente={cliente} puntos={[]} onSeleccionar={() => {}} onTeselasFallidas={onTeselasFallidas} />);

  it('429 repetidos superan el umbral y avisan UNA sola vez (más de MAX_ERRORES_TESELAS errores)', async () => {
    const cliente = { getBlob: vi.fn().mockRejectedValue(new ErrorApi(429, 'LIMITE_EXCEDIDO')) };
    const alFallar = vi.fn();
    montar(cliente, alFallar);
    await act(vaciar);
    expect(cliente.getBlob.mock.calls.length).toBeGreaterThan(MAX_ERRORES_TESELAS);
    expect(alFallar).toHaveBeenCalledTimes(1);
  });

  it('exactamente MAX_ERRORES_TESELAS fallos no avisan (el umbral +1 lo cubre PaginaMapa.test)', async () => {
    let i = 0;
    const cliente = { getBlob: vi.fn(() => (i++ < MAX_ERRORES_TESELAS ? Promise.reject(new ErrorApi(502)) : new Promise(() => {}))) };
    const alFallar = vi.fn();
    montar(cliente, alFallar);
    await act(vaciar);
    expect(alFallar).not.toHaveBeenCalled();
  });

  it('abortos (desmontar) no cuentan para el aviso', async () => {
    const cliente = { getBlob: vi.fn((_r, { signal }) => new Promise((_res, rej) => signal.addEventListener('abort', () => rej(new DOMException('a', 'AbortError'))))) };
    const alFallar = vi.fn();
    const { unmount } = montar(cliente, alFallar);
    await act(vaciar);
    unmount();
    await act(vaciar);
    expect(alFallar).not.toHaveBeenCalled();
  });

  it('cero peticiones a OSM: ningún <img> con src http(s) externo en el mapa', async () => {
    const cliente = { getBlob: vi.fn(() => new Promise(() => {})) };
    montar(cliente);
    await act(vaciar);
    const imgs = [...document.querySelectorAll('img')].map((i) => i.getAttribute('src') || '');
    expect(imgs.filter((s) => /^https?:/.test(s) && !s.includes('/leaflet'))).toEqual([]);
  });
});

describe('QA teselas: capa aislada', () => {
  it('las teselas llevan alt vacío y role presentation (accesibilidad)', () => {
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente: { getBlob: () => new Promise(() => {}) } });
    const img = capa.createTile({ x: 0, y: 0, z: 3 }, () => {});
    expect(img.getAttribute('alt')).toBe('');
    expect(img.getAttribute('role')).toBe('presentation');
  });

  it('un 401 con aborto previo de la tesela no avisa (el aborto gana)', async () => {
    const alSesionVencida = vi.fn();
    let rech;
    const capa = new CapaTeselasGateway(RUTA_TESELAS, { cliente: { getBlob: () => new Promise((_r, rj) => { rech = rj; }) }, alSesionVencida });
    const img = capa.createTile({ x: 0, y: 0, z: 3 }, vi.fn());
    await vaciar();
    capa.fire('tileunload', { tile: img });
    rech(new ErrorApi(401));
    await vaciar();
    expect(alSesionVencida).not.toHaveBeenCalled();
  });
});

describe('QA 20f (SEC-J-24): zoom >= 9 sin caché, de punta a punta', () => {
  const respuestaPng = () => ({ ok: true, blob: () => Promise.resolve(PNG()) });
  const fetchPorZoom = async (zoom) => {
    const f = vi.fn().mockResolvedValue(respuestaPng());
    vi.stubGlobal('fetch', f);
    const cliente = crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => 'TOKEN' });
    const { unmount } = render(
      <MapContainer center={[4.5, -74.1]} zoom={zoom} maxZoom={12} style={{ height: 512, width: 512 }}>
        <TeselasGateway cliente={cliente} attribution="OSM" maxZoom={12} />
      </MapContainer>,
    );
    await act(vaciar);
    await act(vaciar);
    unmount();
    return f.mock.calls;
  };
  afterEach(() => vi.unstubAllGlobals());

  it.each([[8, false], [9, true], [12, true]])('mapa real con cliente real a zoom %i: cache no-store = %s, con Bearer, omit, Accept png y señal', async (z, esperado) => {
    const llamadas = await fetchPorZoom(z);
    expect(llamadas.length).toBeGreaterThan(0);
    for (const [url, op] of llamadas) {
      expect(url).toMatch(new RegExp(`^http://gw/api/gateway/teselas/${z}/\\d+/\\d+\\.png$`));
      if (esperado) expect(op.cache).toBe('no-store');
      else expect(op).not.toHaveProperty('cache');
      expect(op.headers.Authorization).toBe('Bearer TOKEN');
      expect(op.headers.Accept).toBe('image/png');
      expect(op.credentials).toBe('omit');
      expect(op.signal).toBeInstanceOf(AbortSignal);
    }
  });

  it('getBlob con sinCache false/undefined explícito no manda cache; get sigue con no-store, Bearer y omit', async () => {
    const f = vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(PNG()), json: () => Promise.resolve({}) });
    vi.stubGlobal('fetch', f);
    const c = crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => 'T' });
    await c.getBlob('/t', { sinCache: false });
    await c.getBlob('/t', { sinCache: undefined });
    await c.get('/x');
    const [[, a], [, b], [, g]] = f.mock.calls;
    expect(a).not.toHaveProperty('cache');
    expect(b).not.toHaveProperty('cache');
    expect(g.cache).toBe('no-store');
    expect(g.headers).toEqual({ Authorization: 'Bearer T', Accept: 'application/json' });
    expect(g.credentials).toBe('omit');
  });

  it('con sinCache los errores se siguen traduciendo (401 -> ErrorApi 401)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, json: () => Promise.reject(new Error('x')) }));
    const c = crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => 'T' });
    await expect(c.getBlob('/t', { sinCache: true })).rejects.toMatchObject({ status: 401 });
  });
});
