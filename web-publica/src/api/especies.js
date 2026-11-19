import { API_URL, getJson } from './cliente.js';

/**
 * Catálogo público agrupado por especie (GET /api/publica/especies, D65). LISTA BLANCA como en fichas.js
 * (02 §9.1): solo estas claves llegan a la UI, nunca coordenadas ni el investigador.
 */
export const TAMANO_PAGINA = 12;

const texto = (v) => (typeof v === 'string' && v.trim() !== '' ? v : null);
const textos = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim() !== '') : []);

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

/** URL de la foto pública de una especie (JPEG reducido). Si no tiene, responde 404 y se muestra la ilustración. */
export function urlFotoEspecie(nombreCientifico) {
  return `${API_URL}/api/publica/especies/foto?nombre=${encodeURIComponent(nombreCientifico)}`;
}
