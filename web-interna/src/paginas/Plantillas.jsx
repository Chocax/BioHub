import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/cliente.js';
import { puede } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import { Aviso, AvisoError, CabeceraPagina, Cargando, formatearFecha, Vacio } from '../componentes/ui.jsx';

const TIPOS = [['TEXTO', 'Texto'], ['NUMERO', 'Número decimal'], ['ENTERO', 'Número entero'], ['BOOLEANO', 'Sí / no'], ['FECHA', 'Fecha']];

/** Clave estable a partir de la etiqueta: "Temperatura del agua" → "temperatura_del_agua". */
export function claveDesde(etiqueta) {
  const base = etiqueta.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').replace(/^[^a-z]+/, '');
  return base.slice(0, 60) || 'atributo';
}

/** HU-02 y RNF8: agregar, renombrar o deshabilitar atributos de la ficha de una especie, sin programar. */
export default function Plantillas() {
  const { roles } = useSesion();
  const editable = puede(roles, 'editarPlantillas');
  const [parametros, setParametros] = useSearchParams();
  const [especie, setEspecie] = useState(parametros.get('especie') ?? '');
  const [plantilla, setPlantilla] = useState(null);
  const [filas, setFilas] = useState([]);
  const [estado, setEstado] = useState('inicial');
  const [error, setError] = useState(null);
  const [mensaje, setMensaje] = useState(null);

  async function buscar(e) {
    e?.preventDefault();
    const nombre = especie.trim();
    if (nombre.length < 3) return;
    setParametros({ especie: nombre });
    setEstado('cargando'); setError(null); setMensaje(null);
    try {
      const p = await api.get(`/api/cientifico/fichas/${encodeURIComponent(nombre)}/atributos`);
      setPlantilla(p);
      setFilas(p.atributos.map((a) => ({ ...a, guardado: true })));
      setEstado('lista');
    } catch (falla) {
      if (falla.status === 404) { setPlantilla(null); setFilas([]); setEstado('nueva'); } else { setError(falla); setEstado('inicial'); }
    }
  }

  const cambiar = (i, campo, valor) => setFilas((f) => f.map((fila, j) => {
    if (j !== i) return fila;
    const nueva = { ...fila, [campo]: valor };
    if (campo === 'etiqueta' && !fila.guardado) nueva.clave = claveDesde(valor);
    return nueva;
  }));

  async function guardar() {
    setError(null); setMensaje(null);
    const vacias = filas.filter((f) => !f.etiqueta.trim());
    if (vacias.length) { setError(new Error('Cada atributo necesita un nombre.')); return; }
    const claves = filas.map((f) => f.clave);
    if (new Set(claves).size !== claves.length) { setError(new Error('Hay dos atributos con el mismo nombre interno; cambia uno.')); return; }
    try {
      const cuerpo = filas.map(({ clave, etiqueta, tipo, obligatorio, habilitado, unidad }) => ({
        clave, etiqueta: etiqueta.trim(), tipo, obligatorio, habilitado, unidad: unidad?.trim() || null,
      }));
      const p = await api.put(`/api/cientifico/fichas/${encodeURIComponent(especie.trim())}/atributos`, cuerpo);
      setPlantilla(p);
      setFilas(p.atributos.map((a) => ({ ...a, guardado: true })));
      setEstado('lista');
      setMensaje(`Ficha guardada como versión ${p.version}. Los hallazgos anteriores conservan sus datos.`);
    } catch (falla) {
      setError(falla);
    }
  }

  return (
    <section className="pagina">
      <CabeceraPagina pestana="Fichas taxonómicas" eyebrow="Fichas taxonómicas · HU-02" titulo={<>Atributos <em>flexibles</em></>}
        descripcion="Cada especie mide cosas distintas: la temperatura del agua de una rana, el canto de un ave, una muestra de ADN. Aquí defines qué campos pide el formulario de registro." />
      <form className="tarjeta acciones" onSubmit={buscar} style={{ alignItems: 'flex-end' }}>
        <div className="campo" style={{ flex: '1 1 260px' }}>
          <label htmlFor="especie">Nombre científico de la especie</label>
          <input id="especie" lang="la" value={especie} onChange={(e) => setEspecie(e.target.value)} placeholder="Atelopus ebenoides" />
        </div>
        <button type="submit" className="boton" disabled={especie.trim().length < 3}>Abrir ficha</button>
      </form>

      <div style={{ marginTop: 16 }}>
        <AvisoError error={error} />
        {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
        {estado === 'cargando' && <Cargando />}
        {estado === 'inicial' && !error && <Vacio titulo="Abre la ficha de una especie">Escribe el nombre científico para ver sus atributos.</Vacio>}
        {(estado === 'lista' || estado === 'nueva') && (
          <div className="tarjeta">
            <div className="acciones" style={{ justifyContent: 'space-between' }}>
              <div>
                <p className="tarjeta__titulo"><i lang="la">{especie.trim()}</i></p>
                <p className="texto-suave" style={{ margin: 0 }}>
                  {plantilla ? `Versión ${plantilla.version} · ${plantilla.creadoPor} · ${formatearFecha(plantilla.creadoEn, true)}` : 'Esta especie todavía no tiene ficha: crea la primera versión.'}
                </p>
              </div>
              {editable && (
                <button type="button" className="boton boton--fantasma"
                  onClick={() => setFilas((f) => [...f, { clave: 'atributo', etiqueta: '', tipo: 'TEXTO', obligatorio: false, habilitado: true, unidad: '', guardado: false }])}>
                  Agregar atributo
                </button>
              )}
            </div>
            {filas.length === 0 && <p className="texto-suave" style={{ marginTop: 16 }}>Sin atributos todavía.</p>}
            {filas.length > 0 && (
              <div className="tabla-envoltura" style={{ marginTop: 16 }}>
                <table className="tabla">
                  <thead><tr><th>Nombre visible</th><th>Tipo</th><th>Unidad</th><th>Obligatorio</th><th>Activo</th>{editable && <th><span className="solo-lectores">Acción</span></th>}</tr></thead>
                  <tbody>
                    {filas.map((f, i) => (
                      <tr key={`${f.clave}-${i}`} style={f.habilitado ? undefined : { opacity: 0.55 }}>
                        <td>
                          {editable
                            ? <input aria-label={`Nombre del atributo ${i + 1}`} value={f.etiqueta} onChange={(e) => cambiar(i, 'etiqueta', e.target.value)} className="celda-entrada" />
                            : f.etiqueta}
                          <div className="mono texto-suave">{f.clave}</div>
                        </td>
                        <td>
                          {editable && !f.guardado
                            ? <select aria-label={`Tipo del atributo ${i + 1}`} value={f.tipo} onChange={(e) => cambiar(i, 'tipo', e.target.value)} className="celda-entrada">{TIPOS.map(([v, t]) => <option key={v} value={v}>{t}</option>)}</select>
                            : TIPOS.find(([v]) => v === f.tipo)?.[1]}
                        </td>
                        <td>{editable ? <input aria-label={`Unidad del atributo ${i + 1}`} value={f.unidad ?? ''} onChange={(e) => cambiar(i, 'unidad', e.target.value)} className="celda-entrada" style={{ width: 80 }} /> : (f.unidad || '—')}</td>
                        <td><input type="checkbox" aria-label={`Obligatorio: ${f.etiqueta}`} checked={f.obligatorio} disabled={!editable} onChange={(e) => cambiar(i, 'obligatorio', e.target.checked)} /></td>
                        <td><input type="checkbox" aria-label={`Activo: ${f.etiqueta}`} checked={f.habilitado} disabled={!editable} onChange={(e) => cambiar(i, 'habilitado', e.target.checked)} /></td>
                        {editable && <td>{!f.guardado && <button type="button" className="enlace" onClick={() => setFilas((l) => l.filter((_, j) => j !== i))}>Quitar</button>}</td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {editable && (
              <>
                <p className="texto-suave" style={{ fontSize: 12.5 }}>
                  Un atributo ya guardado no se borra: se desactiva. Así los hallazgos antiguos conservan su valor.
                </p>
                <button type="button" className="boton" onClick={guardar} disabled={filas.length === 0}>Guardar nueva versión</button>
              </>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
