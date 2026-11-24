import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { ArrowLeft, FileText, MapPin, Plus, Save, Search, Trash2 } from 'lucide-react';
import { api } from '../api/cliente.js';
import { useSugerencias } from '../api/sugerencias.js';
import { puede } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import { FotoEvidencia } from '../componentes/Foto.jsx';
import { Aviso, AvisoError, CabeceraPagina, Cargando, ChipEstado, formatearFecha, Paginacion, Vacio } from '../componentes/ui.jsx';

const TIPOS = [['TEXTO', 'Texto'], ['NUMERO', 'Número decimal'], ['ENTERO', 'Número entero'], ['BOOLEANO', 'Sí / no'], ['FECHA', 'Fecha']];
const POR_PAGINA = 12;

/** Clave estable a partir de la etiqueta: "Temperatura del agua" → "temperatura_del_agua". */
export function claveDesde(etiqueta) {
  const base = etiqueta.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').replace(/^[^a-z]+/, '');
  return base.slice(0, 60) || 'atributo';
}

// Identificador de fila que no cambia al escribir: si la key dependiera de la clave (que sale de la etiqueta),
// React volvería a crear el campo en cada tecla y se perdería el foco.
let siguienteUid = 0;
const conUid = (a) => ({ ...a, uid: `fila-${siguienteUid++}` });

/** Tarjetas de especies (GET /api/cientifico/especies, D65): una por especie, con su foto y su ficha. */
function Galeria({ alAbrir }) {
  const sugerencias = useSugerencias();
  const [texto, setTexto] = useState('');
  const [consulta, setConsulta] = useState({ q: '', familia: '', page: 0 });
  const [pagina, setPagina] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    const q = new URLSearchParams({ page: consulta.page, size: POR_PAGINA });
    if (consulta.q) q.set('q', consulta.q);
    if (consulta.familia) q.set('familia', consulta.familia);
    setError(null);
    api.get(`/api/cientifico/especies?${q}`).then(setPagina).catch(setError);
  }, [consulta]);

  return (
    <section aria-label="Especies con hallazgos o ficha">
      <form className="filtros-galeria" onSubmit={(e) => { e.preventDefault(); setConsulta((c) => ({ ...c, q: texto.trim(), page: 0 })); }}>
        <div className="campo">
          <label htmlFor="buscar-especie">Buscar especie</label>
          <input id="buscar-especie" value={texto} onChange={(e) => setTexto(e.target.value)} placeholder="Nombre científico o común" autoComplete="off" />
        </div>
        <div className="campo">
          <label htmlFor="filtro-familia">Familia</label>
          <select id="filtro-familia" value={consulta.familia} onChange={(e) => setConsulta((c) => ({ ...c, familia: e.target.value, page: 0 }))}>
            <option value="">Todas las familias</option>
            {(sugerencias?.familias ?? []).map((f) => <option key={f} value={f}>{f}</option>)}
          </select>
        </div>
        <button type="submit" className="boton"><Search className="icono" aria-hidden="true" />Buscar</button>
      </form>
      <AvisoError error={error} />
      {!pagina && !error && <Cargando texto="Cargando especies…" />}
      {pagina?.content.length === 0 && <Vacio titulo="Ninguna especie coincide">Prueba con otro nombre o abre una ficha nueva arriba.</Vacio>}
      {pagina?.content.length > 0 && (
        <>
          <ul className="galeria">
            {pagina.content.map((e) => (
              <li key={e.nombreCientifico}>
                <button type="button" className="especie" onClick={() => alAbrir(e.nombreCientifico)}
                  aria-label={`Abrir la ficha de ${e.nombreCientifico}`}>
                  <div className="especie__foto">
                    <FotoEvidencia foto={e.foto} alt="" />
                    {e.estadoConservacion && <span className="especie__sello"><ChipEstado estado={e.estadoConservacion} /></span>}
                  </div>
                  <div className="especie__cuerpo">
                    <p className="especie__nombre" lang="la">{e.nombreCientifico}</p>
                    <p className="especie__comun">{e.nombreComun ?? 'Sin nombre común'}{e.familia ? ` · ${e.familia}` : ''}</p>
                    <div className="especie__meta">
                      <span><MapPin className="icono" aria-hidden="true" />{e.registros} {e.registros === 1 ? 'registro' : 'registros'}</span>
                      <span><FileText className="icono" aria-hidden="true" />
                        {e.fichaVersion ? `Ficha v${e.fichaVersion} · ${e.atributosFicha} ${e.atributosFicha === 1 ? 'atributo' : 'atributos'}` : 'Sin ficha'}
                      </span>
                    </div>
                    {e.departamentos?.length > 0 && <p className="especie__comun" style={{ fontSize: 12 }}>{e.departamentos.join(', ')}</p>}
                  </div>
                </button>
              </li>
            ))}
          </ul>
          <Paginacion pagina={pagina.page} totalPaginas={pagina.totalPaginas} total={pagina.total}
            alCambiar={(page) => setConsulta((c) => ({ ...c, page }))} />
        </>
      )}
    </section>
  );
}

/** Editor de la ficha de una especie: agregar, renombrar o desactivar atributos (HU-02, RNF8). */
function EditorFicha({ especie, editable, alVolver }) {
  const [plantilla, setPlantilla] = useState(null);
  const [foto, setFoto] = useState(undefined);
  const [filas, setFilas] = useState([]);
  const [estado, setEstado] = useState('cargando');
  const [error, setError] = useState(null);
  const [mensaje, setMensaje] = useState(null);
  const nuevaFila = useRef(null);

  const cargar = useCallback(async () => {
    setEstado('cargando'); setError(null);
    try {
      const p = await api.get(`/api/cientifico/fichas/${encodeURIComponent(especie)}/atributos`);
      setPlantilla(p);
      setFilas(p.atributos.map((a) => conUid({ ...a, guardado: true })));
      setEstado('lista');
    } catch (falla) {
      if (falla.status === 404) { setPlantilla(null); setFilas([]); setEstado('nueva'); } else { setError(falla); setEstado('error'); }
    }
  }, [especie]);
  useEffect(() => { cargar(); }, [cargar]);
  useEffect(() => {
    api.get(`/api/cientifico/especies?q=${encodeURIComponent(especie)}&size=12`)
      .then((p) => setFoto(p.content.find((e) => e.nombreCientifico.toLowerCase() === especie.toLowerCase())?.foto ?? null))
      .catch(() => setFoto(null));
  }, [especie]);
  useEffect(() => { nuevaFila.current?.focus(); }, [filas.length]);

  const cambiar = (uid, campo, valor) => setFilas((f) => f.map((fila) => {
    if (fila.uid !== uid) return fila;
    const nueva = { ...fila, [campo]: valor };
    if (campo === 'etiqueta' && !fila.guardado) nueva.clave = claveDesde(valor);
    return nueva;
  }));

  async function guardar() {
    setError(null); setMensaje(null);
    if (filas.some((f) => !f.etiqueta.trim())) { setError(new Error('Cada atributo necesita un nombre.')); return; }
    const claves = filas.map((f) => f.clave);
    if (new Set(claves).size !== claves.length) { setError(new Error('Hay dos atributos con el mismo nombre interno; cambia uno.')); return; }
    try {
      const cuerpo = filas.map(({ clave, etiqueta, tipo, obligatorio, habilitado, unidad }) => ({
        clave, etiqueta: etiqueta.trim(), tipo, obligatorio, habilitado, unidad: unidad?.trim() || null,
      }));
      const p = await api.put(`/api/cientifico/fichas/${encodeURIComponent(especie)}/atributos`, cuerpo);
      setPlantilla(p);
      setFilas(p.atributos.map((a) => conUid({ ...a, guardado: true })));
      setEstado('lista');
      setMensaje(`Ficha guardada como versión ${p.version}. Los hallazgos anteriores conservan sus datos.`);
    } catch (falla) {
      setError(falla);
    }
  }

  const ultimaNueva = [...filas].reverse().find((f) => !f.guardado)?.uid;

  return (
    <div className="tarjeta">
      <button type="button" className="enlace" onClick={alVolver} style={{ display: 'inline-flex', gap: 6, alignItems: 'center', marginBottom: 16 }}>
        <ArrowLeft className="icono" aria-hidden="true" />Volver a todas las especies
      </button>
      <div className="ficha-cabeza">
        <div className="ficha-cabeza__foto">{foto !== undefined && <FotoEvidencia foto={foto} alt={`Foto de ${especie}`} />}</div>
        <div>
          <p className="eyebrow">Ficha taxonómica</p>
          <p className="titulo-pagina" style={{ fontSize: 'clamp(28px, 4vw, 40px)' }}><i lang="la">{especie}</i></p>
          <p className="texto-suave" style={{ margin: '8px 0 0' }}>
            {plantilla ? `Versión ${plantilla.version} · ${plantilla.creadoPor} · ${formatearFecha(plantilla.creadoEn, true)}` : 'Esta especie todavía no tiene ficha: crea la primera versión.'}
          </p>
        </div>
      </div>
      <AvisoError error={error} />
      {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
      {estado === 'cargando' && <Cargando />}
      {(estado === 'lista' || estado === 'nueva') && (
        <>
          <div className="acciones" style={{ justifyContent: 'space-between' }}>
            <p className="tarjeta__titulo" style={{ fontSize: 20 }}>Atributos</p>
            {editable && (
              <button type="button" className="boton boton--fantasma"
                onClick={() => setFilas((f) => [...f, conUid({ clave: 'atributo', etiqueta: '', tipo: 'TEXTO', obligatorio: false, habilitado: true, unidad: '', guardado: false })])}>
                <Plus className="icono" aria-hidden="true" />Agregar atributo
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
                    <tr key={f.uid} style={f.habilitado ? undefined : { opacity: 0.55 }}>
                      <td>
                        {editable
                          ? <input aria-label={`Nombre del atributo ${i + 1}`} value={f.etiqueta} placeholder="Ej. Temperatura del agua"
                              ref={f.uid === ultimaNueva ? nuevaFila : undefined}
                              onChange={(e) => cambiar(f.uid, 'etiqueta', e.target.value)} className="celda-entrada celda-entrada--ancha" />
                          : f.etiqueta}
                        <div className="mono texto-suave">{f.clave}</div>
                      </td>
                      <td>
                        {editable && !f.guardado
                          ? <select aria-label={`Tipo del atributo ${i + 1}`} value={f.tipo} onChange={(e) => cambiar(f.uid, 'tipo', e.target.value)} className="celda-entrada">{TIPOS.map(([v, t]) => <option key={v} value={v}>{t}</option>)}</select>
                          : TIPOS.find(([v]) => v === f.tipo)?.[1]}
                      </td>
                      <td>{editable
                        ? <input aria-label={`Unidad del atributo ${i + 1}`} value={f.unidad ?? ''} placeholder="mm, °C, kg…" onChange={(e) => cambiar(f.uid, 'unidad', e.target.value)} className="celda-entrada celda-entrada--unidad" />
                        : (f.unidad || '—')}</td>
                      <td><input type="checkbox" aria-label={`Obligatorio: ${f.etiqueta}`} checked={f.obligatorio} disabled={!editable} onChange={(e) => cambiar(f.uid, 'obligatorio', e.target.checked)} /></td>
                      <td><input type="checkbox" aria-label={`Activo: ${f.etiqueta}`} checked={f.habilitado} disabled={!editable} onChange={(e) => cambiar(f.uid, 'habilitado', e.target.checked)} /></td>
                      {editable && <td>{!f.guardado && (
                        <button type="button" className="enlace" onClick={() => setFilas((l) => l.filter((x) => x.uid !== f.uid))}
                          style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
                          <Trash2 className="icono" aria-hidden="true" />Quitar
                        </button>
                      )}</td>}
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
              <button type="button" className="boton" onClick={guardar} disabled={filas.length === 0}>
                <Save className="icono" aria-hidden="true" />Guardar nueva versión
              </button>
            </>
          )}
        </>
      )}
    </div>
  );
}

/** HU-02 y RNF8: las fichas de todas las especies, y su editor de atributos. */
export default function Plantillas() {
  const { roles } = useSesion();
  const editable = puede(roles, 'editarPlantillas');
  const [parametros, setParametros] = useSearchParams();
  const especie = parametros.get('especie');
  const sugerencias = useSugerencias();
  const [nueva, setNueva] = useState('');
  const idLista = useId();

  const abrir = (nombre) => { setParametros({ especie: nombre.trim() }); window.scrollTo({ top: 0, behavior: 'smooth' }); };

  return (
    <section className="pagina">
      <CabeceraPagina pestana="Fichas taxonómicas" eyebrow="Fichas taxonómicas · HU-02" titulo={<>Atributos <em>flexibles</em></>} pintura="auto"
        descripcion="Cada especie mide cosas distintas: la temperatura del agua de una rana, el canto de un ave, una muestra de ADN. Elige una especie para ver o cambiar los campos que pide el formulario de registro." />
      {especie ? (
        <EditorFicha key={especie} especie={especie} editable={editable} alVolver={() => setParametros({})} />
      ) : (
        <>
          {editable && (
            <form className="tarjeta acciones" style={{ alignItems: 'flex-end', marginBottom: 24 }}
              onSubmit={(e) => { e.preventDefault(); if (nueva.trim().length >= 3) abrir(nueva); }}>
              <div className="campo" style={{ flex: '1 1 260px' }}>
                <label htmlFor="nueva-especie">¿Una especie que no está en la lista? Escribe su nombre científico</label>
                <input id="nueva-especie" lang="la" value={nueva} list={idLista} onChange={(e) => setNueva(e.target.value)} placeholder="Atelopus ebenoides" autoComplete="off" />
                <datalist id={idLista}>{(sugerencias?.especies ?? []).map((e) => <option key={e.nombreCientifico} value={e.nombreCientifico} />)}</datalist>
              </div>
              <button type="submit" className="boton" disabled={nueva.trim().length < 3}><FileText className="icono" aria-hidden="true" />Abrir o crear ficha</button>
            </form>
          )}
          <Galeria alAbrir={abrir} />
        </>
      )}
    </section>
  );
}
