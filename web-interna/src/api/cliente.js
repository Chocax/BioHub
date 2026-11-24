import { config } from '../config.js';
import { keycloak } from '../auth/keycloak.js';

/**
 * Error de la API con lo que trae el problem+json del backend (04-contrato-api.md §2):
 * `status`, `codigo` (p. ej. PARAMETRO_INVALIDO), `detalle` y `errores[{campo, mensaje}]`.
 * `status` 0 = no hubo respuesta (red o servidor caído).
 */
export class ErrorApi extends Error {
  constructor(status, cuerpo = {}) {
    super(cuerpo.detail || cuerpo.detalle || mensajePorEstado(status));
    this.status = status;
    this.codigo = cuerpo.codigo ?? null;
    this.errores = Array.isArray(cuerpo.errores) ? cuerpo.errores : [];
    this.reintentarEn = cuerpo.reintentarEn ?? null;
  }
}

function mensajePorEstado(status) {
  if (status === 0) return 'No hay conexión con BioHub. Revisa tu red e intenta de nuevo.';
  if (status === 401) return 'Tu sesión terminó. Vuelve a iniciar sesión.';
  if (status === 403) return 'Tu rol no tiene permiso para esta acción.';
  if (status === 404) return 'No encontramos lo que buscas.';
  if (status === 409) return 'La acción no se pudo completar por el estado actual del recurso.';
  if (status === 413) return 'El archivo es demasiado grande.';
  if (status === 429) return 'Demasiadas peticiones seguidas. Espera un momento.';
  if (status >= 500) return 'El servicio no está disponible en este momento. Intenta de nuevo.';
  return 'No se pudo completar la acción.';
}

/** Token vigente: lo renueva si le quedan menos de 30 s (duran 5 minutos). */
export async function obtenerToken() {
  try {
    await keycloak.updateToken(30);
  } catch {
    await keycloak.login();
    throw new ErrorApi(401);
  }
  return keycloak.token;
}

/**
 * Llama al API Gateway con el JWT.
 * @param {string} metodo GET, POST, PUT, DELETE
 * @param {string} ruta empieza con /api/...
 * @param {{ cuerpo?: any, formulario?: FormData, como?: 'json'|'blob'|'nada', senal?: AbortSignal }} opciones
 */
export async function pedir(metodo, ruta, { cuerpo, formulario, como = 'json', senal } = {}) {
  const token = await obtenerToken();
  const cabeceras = { Authorization: `Bearer ${token}`, Accept: como === 'blob' ? '*/*' : 'application/json' };
  let body;
  if (formulario) {
    body = formulario; // el navegador pone el Content-Type multipart con su boundary
  } else if (cuerpo !== undefined) {
    cabeceras['Content-Type'] = 'application/json';
    body = JSON.stringify(cuerpo);
  }
  let respuesta;
  try {
    respuesta = await fetch(`${config.apiUrl}${ruta}`, {
      method: metodo, headers: cabeceras, body, signal: senal, cache: 'no-store', credentials: 'omit',
    });
  } catch (e) {
    if (e?.name === 'AbortError') throw e;
    throw new ErrorApi(0);
  }
  if (respuesta.status === 401) {
    keycloak.login();
    throw new ErrorApi(401);
  }
  if (!respuesta.ok) {
    let detalle = {};
    try { detalle = await respuesta.json(); } catch { /* sin cuerpo */ }
    const reintentar = Number(respuesta.headers.get('Retry-After'));
    throw new ErrorApi(respuesta.status, { ...detalle, reintentarEn: Number.isFinite(reintentar) && reintentar > 0 ? reintentar : null });
  }
  if (como === 'nada' || respuesta.status === 204) return null;
  if (como === 'blob') return respuesta.blob();
  const texto = await respuesta.text();
  return texto ? JSON.parse(texto) : null;
}

export const api = {
  get: (ruta, opciones) => pedir('GET', ruta, opciones),
  post: (ruta, cuerpo, opciones) => pedir('POST', ruta, { ...opciones, cuerpo }),
  put: (ruta, cuerpo, opciones) => pedir('PUT', ruta, { ...opciones, cuerpo }),
  borrar: (ruta, opciones) => pedir('DELETE', ruta, { ...opciones, como: 'nada' }),
  subir: (ruta, formulario, opciones) => pedir('POST', ruta, { ...opciones, formulario }),
};

/** Descarga un archivo autenticado (no se puede con un <a href>: el JWT va en la cabecera). */
export async function descargar(ruta, nombre) {
  const blob = await pedir('GET', ruta, { como: 'blob' });
  const url = URL.createObjectURL(blob);
  const enlace = document.createElement('a');
  enlace.href = url;
  enlace.download = nombre;
  document.body.appendChild(enlace);
  enlace.click();
  enlace.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Compatibilidad con el ping de la versión anterior. */
export function getAutenticado(ruta) {
  return api.get(ruta);
}
