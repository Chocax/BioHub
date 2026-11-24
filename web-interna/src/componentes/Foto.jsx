import { useEffect, useState } from 'react';
import { pedir } from '../api/cliente.js';
import { Frailejon } from './Ilustraciones.jsx';

// Las fotos se piden con el JWT (no se pueden poner en <img src> directo) y se guardan como object URL. La caché
// evita volver a bajar la misma foto al paginar o volver a una sala; con tope, para no acumular memoria.
const cache = new Map();
const MAXIMO = 80;

export function urlEvidencia(hallazgoId, evidenciaId) {
  const clave = `${hallazgoId}/${evidenciaId}`;
  if (!cache.has(clave)) {
    const promesa = pedir('GET', `/api/cientifico/hallazgos/${hallazgoId}/evidencias/${evidenciaId}`, { como: 'blob' })
      .then((blob) => URL.createObjectURL(blob));
    promesa.catch(() => cache.delete(clave));
    cache.set(clave, promesa);
    if (cache.size > MAXIMO) {
      const [primera, vieja] = cache.entries().next().value;
      cache.delete(primera);
      vieja.then((url) => URL.revokeObjectURL(url)).catch(() => {});
    }
  }
  return cache.get(clave);
}

export function useFotoEvidencia(hallazgoId, evidenciaId) {
  const [url, setUrl] = useState(null);
  useEffect(() => {
    if (!hallazgoId || !evidenciaId) { setUrl(null); return undefined; }
    let vigente = true;
    urlEvidencia(hallazgoId, evidenciaId).then((u) => { if (vigente) setUrl(u); }).catch(() => { if (vigente) setUrl(null); });
    return () => { vigente = false; };
  }, [hallazgoId, evidenciaId]);
  return url;
}

/** Lugar de la foto cuando una especie todavía no tiene: un frailejón, no un hueco gris. */
export function SinFoto({ texto = 'Sin foto todavía' }) {
  return <div className="sin-foto" role="img" aria-label={texto}><Frailejon /></div>;
}

/** Foto de una evidencia ({ hallazgoId, evidenciaId }) o la ilustración si no hay. */
export function FotoEvidencia({ foto, alt }) {
  const url = useFotoEvidencia(foto?.hallazgoId, foto?.evidenciaId);
  if (!foto) return <SinFoto />;
  if (!url) return <div className="sin-foto" aria-hidden="true" />;
  return <img src={url} alt={alt} loading="lazy" />;
}
