import { useEffect } from 'react';
import { fechaCorta, formatearCoordenada } from '../utilidades/formato.js';
import Boton from './Boton.jsx';
import ChipEstado from './ChipEstado.jsx';

/**
 * Detalle del hallazgo seleccionado (región, no diálogo modal). Esc cierra. Lat/lon solo como texto.
 * `extra(punto)` (opcional) lo pone quien monta el módulo: por ejemplo la foto y el enlace a la ficha.
 */
export default function PanelDetalleHallazgo({ punto, onCerrar, extra }) {
  useEffect(() => {
    if (!punto) return undefined;
    const alTeclear = (e) => {
      if (e.key === 'Escape') onCerrar();
    };
    document.addEventListener('keydown', alTeclear);
    return () => document.removeEventListener('keydown', alTeclear);
  }, [punto, onCerrar]);

  return (
    <aside className="ub-detalle" aria-label="Detalle del hallazgo">
      {punto ? (
        <>
          {extra ? <div className="ub-detalle__extra">{extra(punto)}</div> : null}
          <div className="ub-detalle__cuerpo">
            <ChipEstado estado={punto.estadoConservacion} />
            <h2><i lang="la">{punto.nombreCientifico}</i></h2>
            <dl className="ub-detalle__datos">
              <div><dt>Recolección</dt><dd>{fechaCorta(punto.fechaRecoleccion)}</dd></div>
              <div><dt>Investigador</dt><dd>{punto.investigador}</dd></div>
              <div><dt>Departamento</dt><dd>{punto.departamento}</dd></div>
              <div><dt>Región</dt><dd>{punto.region}</dd></div>
            </dl>
            <div className="ub-detalle__coordenadas">
              <p className="ub-detalle__rotulo">Coordenadas exactas</p>
              <dl>
                <div><dt>Latitud</dt><dd>{formatearCoordenada(punto.posicion[0])}</dd></div>
                <div><dt>Longitud</dt><dd>{formatearCoordenada(punto.posicion[1])}</dd></div>
              </dl>
            </div>
            <Boton variante="secundario" onClick={onCerrar}>Cerrar</Boton>
          </div>
        </>
      ) : (
        <p>Selecciona un marcador para ver el detalle.</p>
      )}
    </aside>
  );
}
