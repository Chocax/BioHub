import { geoJsonALeaflet } from '../utilidades/geo.js';

/** Error de la API: `status` (0 = sin respuesta), `codigo` estable del contrato (problem+json). */
export class ErrorApi extends Error {
  constructor(status, codigo = null) {
    super(`La API respondió ${status}`);
    this.name = 'ErrorApi';
    this.status = status;
    this.codigo = codigo;
  }
}

/**
 * Cliente HTTP inyectable. Contrato que espera el módulo:
 *   cliente.get(ruta, { signal })     -> Promise<json>   (JSON de la API)
 *   cliente.getBlob(ruta, { signal, sinCache }) -> Promise<Blob>   (teselas del mapa, SEC-J-04;
 *                                     con sinCache: true no debe usar la caché, SEC-J-24/D57)
 * Ambos rechazan con un error que tenga `.status` (0 = sin respuesta; 401, 403, 429, 5xx...) y,
 * si lo conocen, `.codigo`. Un AbortError del `signal` se propaga tal cual.
 * Un cliente propio (no el de `crearClienteUbicacion`) DEBE implementar también
 * `getBlob(ruta, { signal, sinCache })` y, con `sinCache: true`, no guardar la respuesta en caché
 * (SEC-J-24). Sin `getBlob` el mapa base no cargará (la vista Lista seguirá funcionando).
 * `crearClienteUbicacion` da una implementación lista con `fetch`; Santiago solo aporta
 * `apiUrl` y `obtenerToken` (por ejemplo: async () => { await keycloak.updateToken(30); return keycloak.token; }).
 */
export function crearClienteUbicacion({ apiUrl, obtenerToken }) {
  /** Petición autenticada común: devuelve la respuesta OK o lanza ErrorApi / AbortError. */
  async function pedir(ruta, { signal, accept, opciones = {} }) {
    let token;
    try {
      token = await obtenerToken();
    } catch {
      throw new ErrorApi(401);
    }
    let r;
    try {
      // `opciones` va primero: nada puede sobrescribir el token, `credentials` ni la señal (SEC-J-I25).
      r = await fetch(`${apiUrl}${ruta}`, {
        ...opciones,
        headers: { Authorization: `Bearer ${token}`, Accept: accept },
        credentials: 'omit',
        signal,
      });
    } catch (e) {
      if (e?.name === 'AbortError') throw e;
      throw new ErrorApi(0);
    }
    if (!r.ok) {
      let codigo = null;
      try {
        codigo = (await r.json())?.codigo ?? null;
      } catch {
        // el 401 no lleva cuerpo
      }
      throw new ErrorApi(r.status, codigo);
    }
    return r;
  }

  return {
    async get(ruta, { signal } = {}) {
      const r = await pedir(ruta, { signal, accept: 'application/json', opciones: { cache: 'no-store' } });
      return r.json();
    },
    /**
     * Binarios (teselas PNG). Por defecto respeta la caché del navegador (el gateway manda
     * `private, max-age=86400`). Con `sinCache: true` usa `cache: 'no-store'`: ni se lee ni se
     * guarda en la caché HTTP, para que las teselas de zoom alto no queden en disco (SEC-J-24).
     */
    async getBlob(ruta, { signal, sinCache = false } = {}) {
      const r = await pedir(ruta, { signal, accept: 'image/png', opciones: sinCache ? { cache: 'no-store' } : {} });
      let blob;
      try {
        blob = await r.blob();
      } catch (e) {
        if (e?.name === 'AbortError') throw e;
        throw new ErrorApi(0); // se cortó la descarga
      }
      return blob;
    },
  };
}

/** Normaliza cualquier fallo del cliente a { status, codigo } sin depender del tipo de error. */
export function estadoDeError(e) {
  return { name: e?.name, status: Number.isInteger(e?.status) ? e.status : 0, codigo: e?.codigo ?? null };
}

function consulta(filtros, extra = {}) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries({ ...filtros, ...extra })) {
    if (v !== '' && v != null) p.set(k, String(v));
  }
  return p.toString();
}

const numero = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/**
 * Adaptador de una fila de la búsqueda. Defensa en profundidad (02 §3.4, D21): si el rol no es
 * alto rango, la fila resultante NO contiene latitud ni longitud, venga lo que venga en la respuesta.
 */
export function aHallazgo(crudo, altoRango) {
  const h = {
    id: crudo?.id ?? null,
    nombreCientifico: crudo?.nombreCientifico ?? '',
    fechaRecoleccion: crudo?.fechaRecoleccion ?? '',
    investigador: crudo?.investigador ?? '',
    departamento: crudo?.departamento ?? '',
    region: crudo?.region ?? '',
    estadoConservacion: crudo?.estadoConservacion ?? 'SIN_EVALUAR',
    estadoCoordenadas: 'PROTEGIDAS',
    latitud: null,
    longitud: null,
  };
  if (!altoRango) return h; // siempre "Restringido"
  const lat = numero(crudo?.latitud);
  const lon = numero(crudo?.longitud);
  if (lat !== null && lon !== null) {
    h.estadoCoordenadas = 'VISIBLES';
    h.latitud = lat;
    h.longitud = lon;
  } else {
    h.estadoCoordenadas = crudo?.estadoCoordenadas === 'NO_DISPONIBLES' ? 'NO_DISPONIBLES' : 'SIN_COORDENADAS';
  }
  return h;
}

export async function buscarHallazgos(cliente, { filtros, page, size, altoRango }, { signal } = {}) {
  const d = await cliente.get(`/api/cientifico/ubicacion/hallazgos?${consulta(filtros, { page, size })}`, { signal });
  const contenido = Array.isArray(d?.content) ? d.content : [];
  return {
    hallazgos: contenido.map((c) => aHallazgo(c, altoRango)),
    page: Number.isInteger(d?.page) ? d.page : page,
    total: Number.isFinite(d?.total) ? d.total : contenido.length,
    totalPaginas: Number.isFinite(d?.totalPaginas) ? d.totalPaginas : 1,
    omitidos: altoRango && Number.isFinite(d?.omitidos) ? d.omitidos : 0,
  };
}

/** Solo se llama para alto rango (la página lo comprueba antes). */
export async function obtenerMapa(cliente, { filtros, hallazgoId }, { signal } = {}) {
  const parametros = hallazgoId
    ? consulta({}, { hallazgoId })
    : consulta({ especie: filtros.especie, departamento: filtros.departamento, desde: filtros.desde, hasta: filtros.hasta });
  const d = await cliente.get(`/api/cientifico/ubicacion/mapa${parametros ? `?${parametros}` : ''}`, { signal });
  const features = Array.isArray(d?.features) ? d.features : [];
  const puntos = [];
  for (const f of features) {
    const posicion = geoJsonALeaflet(f?.geometry?.coordinates); // [lon, lat] -> [lat, lon]
    if (!posicion) continue;
    const p = f.properties ?? {};
    puntos.push({
      id: p.hallazgoId ?? f.id,
      posicion,
      nombreCientifico: p.nombreCientifico ?? '',
      fechaRecoleccion: p.fechaRecoleccion ?? '',
      investigador: p.investigador ?? '',
      departamento: p.departamento ?? '',
      region: p.region ?? '',
      estadoConservacion: p.estadoConservacion ?? 'SIN_EVALUAR',
    });
  }
  return {
    puntos,
    total: Number.isFinite(d?.total) ? d.total : puntos.length,
    truncado: d?.truncado === true,
    omitidos: Number.isFinite(d?.omitidos) ? d.omitidos : 0,
    limite: Number.isFinite(d?.limite) ? d.limite : 1000,
  };
}
