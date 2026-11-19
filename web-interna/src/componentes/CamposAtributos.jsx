import { Campo } from './ui.jsx';

/**
 * Campos personalizados de una ficha (HU-02 c.2): se dibujan y validan según la plantilla vigente de la especie.
 * `plantilla.atributos`: [{ clave, etiqueta, tipo: TEXTO|NUMERO|ENTERO|BOOLEANO|FECHA, obligatorio, habilitado, unidad }]
 */
export default function CamposAtributos({ atributos = [], valores, alCambiar, errores = {} }) {
  const visibles = atributos.filter((a) => a.habilitado);
  if (!visibles.length) return null;
  return (
    <fieldset className="grupo">
      <legend>Atributos de la especie</legend>
      <div className="campos">
        {visibles.map((a) => {
          const valor = valores[a.clave] ?? (a.tipo === 'BOOLEANO' ? false : '');
          const etiqueta = a.unidad ? `${a.etiqueta} (${a.unidad})` : a.etiqueta;
          if (a.tipo === 'BOOLEANO') {
            return (
              <label key={a.clave} className="casilla">
                <input type="checkbox" checked={Boolean(valor)} onChange={(e) => alCambiar(a.clave, e.target.checked)} />
                {etiqueta}
              </label>
            );
          }
          const tipo = { NUMERO: 'number', ENTERO: 'number', FECHA: 'date' }[a.tipo] ?? 'text';
          return (
            <Campo key={a.clave} etiqueta={etiqueta} obligatorio={a.obligatorio} error={errores[a.clave]}>
              {(props) => (
                <input {...props} type={tipo} step={a.tipo === 'NUMERO' ? 'any' : a.tipo === 'ENTERO' ? '1' : undefined}
                  value={valor} onChange={(e) => alCambiar(a.clave, e.target.value)} />
              )}
            </Campo>
          );
        })}
      </div>
    </fieldset>
  );
}

/** Convierte los valores del formulario al tipo de cada atributo y devuelve los errores de los obligatorios. */
export function prepararAtributos(atributos = [], valores = {}) {
  const resultado = {};
  const errores = {};
  atributos.filter((a) => a.habilitado).forEach((a) => {
    const crudo = valores[a.clave];
    const vacio = crudo === undefined || crudo === null || crudo === '';
    if (a.tipo === 'BOOLEANO') {
      resultado[a.clave] = Boolean(crudo);
      return;
    }
    if (vacio) {
      if (a.obligatorio) errores[a.clave] = 'Obligatorio';
      return;
    }
    if (a.tipo === 'NUMERO' || a.tipo === 'ENTERO') {
      const n = Number(crudo);
      if (!Number.isFinite(n) || (a.tipo === 'ENTERO' && !Number.isInteger(n))) {
        errores[a.clave] = a.tipo === 'ENTERO' ? 'Debe ser un número entero' : 'Debe ser un número';
        return;
      }
      resultado[a.clave] = n;
      return;
    }
    resultado[a.clave] = String(crudo).trim();
  });
  return { resultado, errores };
}
