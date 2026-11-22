import { useEffect, useState } from 'react';
import { pedirFotoEspecie } from '../api/especies.js';
import { Mariposa } from './Ilustraciones.jsx';

/**
 * Foto pública de la especie (D65/D66) o, si no hay, una ilustración: nunca un hueco.
 * - Sin `fotoId`: ilustración con "sin foto pública" y NINGUNA petición.
 * - 404: la foto ya no es pública (mismo texto).
 * - 429, 5xx o red: texto neutro y botón discreto para reintentar; no se afirma que no haya foto.
 * `reintentable` muestra el botón de reintento (no en las tarjetas: están dentro de un enlace y no admiten botones;
 * allí se reintenta al volver a montarse).
 * La foto se baja con fetch (credentials omit) y se muestra como blob URL propia de este componente, que se revoca
 * solo al desmontar o cambiar de foto (nunca una URL en uso). El navegador cachea la respuesta inmutable.
 */
export default function FotoEspecie({ nombreCientifico, fotoId = null, className = 'foto-especie', prioridad = false, reintentable = false }) {
  const [estado, setEstado] = useState({ tipo: 'cargando' }); // cargando | ok | sinFoto | error
  const [intento, setIntento] = useState(0);

  useEffect(() => {
    if (!fotoId) return undefined;
    const control = new AbortController();
    let url = null;
    setEstado({ tipo: 'cargando' });
    pedirFotoEspecie(fotoId, { signal: control.signal })
      .then((blob) => {
        url = URL.createObjectURL(blob);
        setEstado({ tipo: 'ok', url });
      })
      .catch((e) => {
        if (e?.name === 'AbortError') return;
        setEstado({ tipo: e?.status === 404 ? 'sinFoto' : 'error' });
      });
    return () => {
      control.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [fotoId, intento]);

  if (!fotoId || estado.tipo === 'sinFoto') {
    return (
      <div className={`${className} foto-especie--vacia`} role="img" aria-label="Esta especie todavía no tiene foto pública">
        <Mariposa className="foto-especie__ilustracion" />
      </div>
    );
  }
  if (estado.tipo === 'error') {
    return (
      <div className={`${className} foto-especie--vacia`}>
        <Mariposa className="foto-especie__ilustracion" />
        <span className="foto-especie__aviso" role={reintentable ? undefined : 'img'} aria-label={reintentable ? undefined : 'Foto no disponible por ahora'}>
          Foto no disponible por ahora
        </span>
        {reintentable ? (
          <button type="button" className="foto-especie__reintento" onClick={() => setIntento((n) => n + 1)}>Reintentar</button>
        ) : null}
      </div>
    );
  }
  if (estado.tipo === 'cargando') {
    return <div className={`${className} foto-especie--cargando`} aria-hidden="true" data-prioridad={prioridad || undefined} />;
  }
  return (
    <div className={className}>
      <img src={estado.url} alt={`Fotografía de ${nombreCientifico}`} />
    </div>
  );
}
