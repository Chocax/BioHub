import { API_URL, ErrorApi, getJson } from './cliente.js';

/**
 * Catálogo público agrupado por especie (GET /api/publica/especies, D65). LISTA BLANCA como en fichas.js
 * (02 §9.1): solo estas claves llegan a la UI, nunca coordenadas ni el investigador.
 */
export const TAMANO_PAGINA = 12;

const texto = (v) => (typeof v === 'string' && v.trim() !== '' ? v : null);
const textos = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim() !== '') : []);

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Un UUID válido o null: cualquier otra cosa no llega a la URL de la foto. */
export const idFotoValido = (v) => (typeof v === 'string' && UUID.test(v) ? v : null);

export function aEspeciePublica(crudo) {
  const o = crudo && typeof crudo === 'object' ? crudo : {};
  const departamentos = textos(o.departamentos);
  const regiones = textos(o.regiones);
  return {
    nombreCientifico: texto(o.nombreCientifico),
    nombreComun: texto(o.nombreComun),
    familia: texto(o.familia),
    estadoConservacion: texto(o.estadoConservacion),
    registros: Number.isInteger(o.registros) && o.registros > 0 ? o.registros : 1,
    // Una ficha suelta (forma de /fichas) también sirve: su departamento y región son la lista.
    departamentos: departamentos.length ? departamentos : textos([o.departamento]),
    regiones: regiones.length ? regiones : textos([o.region]),
    ultimaRecoleccion: texto(o.ultimaRecoleccion),
    fichaId: texto(o.fichaId) ?? texto(o.id),
    fotoId: idFotoValido(o.fotoId),
  };
}

export async function buscarEspecies({ q, familia, departamento, page = 0 }, { signal } = {}) {
  const p = new URLSearchParams();
  if (q) p.set('q', q);
  if (familia) p.set('familia', familia);
  if (departamento) p.set('departamento', departamento);
  p.set('page', String(page));
  p.set('size', String(TAMANO_PAGINA));
  const d = await getJson(`/api/publica/especies?${p}`, { signal });
  const contenido = Array.isArray(d?.content) ? d.content : [];
  return {
    especies: contenido.map(aEspeciePublica).filter((e) => e.nombreCientifico && e.fichaId),
    page: Number.isInteger(d?.page) ? d.page : page,
    total: Number.isFinite(d?.total) ? d.total : contenido.length,
    totalPaginas: Number.isFinite(d?.totalPaginas) ? d.totalPaginas : 1,
    modoDegradado: d?.modoDegradado === true,
    filtrosIgnorados: Array.isArray(d?.filtrosIgnorados) ? d.filtrosIgnorados.filter((x) => typeof x === 'string') : [],
  };
}

/** Familias con registros públicos. Si la API no las da, el filtro vuelve a ser un campo de texto. */
export async function listarFamilias({ signal } = {}) {
  const d = await getJson('/api/publica/especies/familias', { signal });
  return textos(d?.familias);
}

/**
 * Foto pública por su identificador (D66, GET /api/publica/especies/fotos/{fotoId}, inmutable). Con fetch se distingue
 * un 404 (ya no es pública) de un 429/5xx/red (falla pasajera). Devuelve un Blob; lanza ErrorApi con el estado.
 */
export async function pedirFotoEspecie(fotoId, { signal } = {}) {
  const id = idFotoValido(fotoId);
  if (!id) throw new ErrorApi(400);
  let r;
  try {
    r = await fetch(`${API_URL}/api/publica/especies/fotos/${id}`, { credentials: 'omit', signal });
  } catch (e) {
    if (e?.name === 'AbortError') throw e;
    throw new ErrorApi(0);
  }
  if (!r.ok) throw new ErrorApi(r.status);
  return r.blob();
}
