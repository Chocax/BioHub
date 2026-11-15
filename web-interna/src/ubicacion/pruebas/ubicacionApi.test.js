import { afterEach, describe, expect, it, vi } from 'vitest';
import { ErrorApi, aHallazgo, crearClienteUbicacion } from '../api/ubicacionApi.js';
import { geoJsonALeaflet } from '../utilidades/geo.js';
import { esAltoRango } from '../utilidades/roles.js';
import { filaAlto } from './ayudas.js';

afterEach(() => vi.unstubAllGlobals());

describe('conversión y adaptadores', () => {
  it('geoJsonALeaflet invierte [lon, lat] a [lat, lon] y rechaza valores inválidos', () => {
    expect(geoJsonALeaflet([-74.08, 4.61])).toEqual([4.61, -74.08]);
    expect(geoJsonALeaflet([-74.08])).toBeNull();
    expect(geoJsonALeaflet(['a', 4])).toBeNull();
    expect(geoJsonALeaflet([-74, 95])).toBeNull();
    expect(geoJsonALeaflet(null)).toBeNull();
  });

  it('solo cientifico_alto_rango es alto rango', () => {
    expect(esAltoRango(['cientifico_alto_rango'])).toBe(true);
    expect(esAltoRango(['administrador', 'investigador_campo'])).toBe(false);
    expect(esAltoRango(undefined)).toBe(false);
  });

  it('aHallazgo: sin alto rango no conserva latitud ni longitud', () => {
    const h = aHallazgo(filaAlto(), false);
    expect(h.latitud).toBeNull();
    expect(h.longitud).toBeNull();
    expect(h.estadoCoordenadas).toBe('PROTEGIDAS');
    expect(JSON.stringify(h)).not.toContain('5.12');
  });

  it('aHallazgo: alto rango conserva coordenadas válidas', () => {
    const h = aHallazgo(filaAlto(), true);
    expect([h.latitud, h.longitud]).toEqual([5.1234567, -73.1234567]);
  });
});

describe('crearClienteUbicacion', () => {
  it('envía Bearer, no-store y sin cookies; solo el token del adaptador', async () => {
    const f = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ ok: 1 }) });
    vi.stubGlobal('fetch', f);
    const cliente = crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => 'TOKEN' });
    expect(await cliente.get('/api/cientifico/ubicacion/mapa')).toEqual({ ok: 1 });
    const [url, op] = f.mock.calls[0];
    expect(url).toBe('http://gw/api/cientifico/ubicacion/mapa');
    expect(op.headers.Authorization).toBe('Bearer TOKEN');
    expect(op.cache).toBe('no-store');
    expect(op.credentials).toBe('omit');
  });

  it('un fallo al obtener el token se convierte en 401 sin llamar a la API', async () => {
    const f = vi.fn();
    vi.stubGlobal('fetch', f);
    const cliente = crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => { throw new Error('x'); } });
    await expect(cliente.get('/x')).rejects.toMatchObject({ status: 401 });
    expect(f).not.toHaveBeenCalled();
  });

  it('errores HTTP conservan status y codigo; red caída es status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403, json: () => Promise.resolve({ codigo: 'ROL_INSUFICIENTE' }) }));
    const cliente = crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => 't' });
    await expect(cliente.get('/x')).rejects.toMatchObject({ status: 403, codigo: 'ROL_INSUFICIENTE' });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('net')));
    await expect(cliente.get('/x')).rejects.toMatchObject({ status: 0 });
  });
});

describe('crearClienteUbicacion.getBlob (teselas)', () => {
  const crear = () => crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => 'TOKEN' });

  it('200: devuelve el Blob; Bearer, Accept image/png, sin cookies y SIN no-store (la caché del navegador sirve)', async () => {
    const png = new Blob(['x'], { type: 'image/png' });
    const f = vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(png) });
    vi.stubGlobal('fetch', f);
    const control = new AbortController();
    expect(await crear().getBlob('/api/gateway/teselas/5/9/15.png', { signal: control.signal })).toBe(png);
    const [url, op] = f.mock.calls[0];
    expect(url).toBe('http://gw/api/gateway/teselas/5/9/15.png');
    expect(op.headers.Authorization).toBe('Bearer TOKEN');
    expect(op.headers.Accept).toBe('image/png');
    expect(op.credentials).toBe('omit');
    expect(op.signal).toBe(control.signal);
    expect(op).not.toHaveProperty('cache');
  });

  it.each([[400, 'PARAMETRO_INVALIDO'], [401, null], [403, 'ROL_INSUFICIENTE'], [429, 'LIMITE_EXCEDIDO'], [502, 'TESELA_NO_DISPONIBLE'], [503, 'SERVICIO_NO_DISPONIBLE']])(
    'HTTP %i: ErrorApi con status y codigo',
    async (status, codigo) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status, json: () => (codigo ? Promise.resolve({ codigo }) : Promise.reject(new Error('sin cuerpo'))) }));
      const e = await crear().getBlob('/x').catch((err) => err);
      expect(e).toBeInstanceOf(ErrorApi);
      expect(e).toMatchObject({ status, codigo });
    },
  );

  it('sin red: ErrorApi con status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('net')));
    await expect(crear().getBlob('/x')).rejects.toMatchObject({ status: 0 });
  });

  it('se corta la descarga del cuerpo: status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.reject(new TypeError('cortado')) }));
    await expect(crear().getBlob('/x')).rejects.toMatchObject({ status: 0 });
  });

  it('abort: el AbortError se propaga sin convertirse en ErrorApi', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('abortado', 'AbortError')));
    const e = await crear().getBlob('/x').catch((err) => err);
    expect(e.name).toBe('AbortError');
    expect(e).not.toBeInstanceOf(ErrorApi);
  });

  it('un fallo al obtener el token es 401 sin llamar al gateway', async () => {
    const f = vi.fn();
    vi.stubGlobal('fetch', f);
    const cliente = crearClienteUbicacion({ apiUrl: 'http://gw', obtenerToken: async () => { throw new Error('x'); } });
    await expect(cliente.getBlob('/x')).rejects.toMatchObject({ status: 401 });
    expect(f).not.toHaveBeenCalled();
  });
});
