import { useEffect, useState } from 'react';
import { listarFamilias } from '../api/especies.js';
import { DEPARTAMENTOS } from '../datos/departamentos.js';
import CampoSelect from './CampoSelect.jsx';
import CampoTexto from './CampoTexto.jsx';

const TEXTO_DEGRADADO =
  'Hay muchas visitas en este momento. Por ahora solo puedes buscar por nombre; los filtros de familia y departamento están desactivados.';

/**
 * Familia y departamento. La familia es una lista con las familias que ya tienen registros (nadie tiene que saberse
 * su nombre); si la API no la da, vuelve a ser un campo de texto. Se desactivan en modo degradado.
 */
export default function FiltrosAvanzados({ familia, departamento, onFamilia, onDepartamento, onLimpiar, degradado }) {
  const [familias, setFamilias] = useState([]);
  useEffect(() => {
    const control = new AbortController();
    listarFamilias({ signal: control.signal }).then(setFamilias).catch(() => setFamilias([]));
    return () => control.abort();
  }, []);

  // Si la URL trae una familia que no está en la lista, se agrega para no perder el filtro elegido.
  const opcionesFamilia = (familia && !familias.includes(familia) ? [familia, ...familias] : familias)
    .map((f) => ({ codigo: f, nombre: f }));

  return (
    <details className="filtros" open>
      <summary>Filtrar por familia o departamento</summary>
      <div className="filtros-campos">
        {familias.length > 0 ? (
          <CampoSelect
            etiqueta="Familia"
            vacio="Todas las familias"
            opciones={opcionesFamilia}
            value={familia}
            disabled={degradado}
            aria-describedby={degradado ? 'ayuda-degradado' : undefined}
            onChange={(e) => onFamilia(e.target.value)}
          />
        ) : (
          <CampoTexto
            etiqueta="Familia"
            value={familia}
            maxLength={100}
            disabled={degradado}
            aria-describedby={degradado ? 'ayuda-degradado' : undefined}
            onChange={(e) => onFamilia(e.target.value)}
          />
        )}
        <CampoSelect
          etiqueta="Departamento"
          vacio="Todos los departamentos"
          opciones={DEPARTAMENTOS}
          value={departamento}
          disabled={degradado}
          aria-describedby={degradado ? 'ayuda-degradado' : undefined}
          onChange={(e) => onDepartamento(e.target.value)}
        />
        {degradado ? <p id="ayuda-degradado" className="campo-ayuda">{TEXTO_DEGRADADO}</p> : null}
        <button type="button" className="boton boton-enlace" onClick={onLimpiar}>Limpiar filtros</button>
      </div>
    </details>
  );
}
