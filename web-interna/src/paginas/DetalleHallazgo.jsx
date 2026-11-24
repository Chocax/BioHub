import { Fragment, useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { Download, Globe, History, Map as IconoMapa, PenLine, Upload } from 'lucide-react';
import { api, descargar, pedir } from '../api/cliente.js';
import { puede } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import CamposAtributos, { prepararAtributos } from '../componentes/CamposAtributos.jsx';
import { CATEGORIAS_UICN } from './NuevoHallazgo.jsx';
import { DEPARTAMENTOS } from '../ubicacion/datos/departamentos.js';
import { Aviso, AvisoError, CabeceraPagina, Campo, Cargando, ChipEstado, formatearFecha, formatearTamano, Vacio } from '../componentes/ui.jsx';

/** Miniatura o reproductor de una evidencia: se pide con el JWT y se muestra desde un object URL. */
function VistaEvidencia({ hallazgoId, evidencia }) {
  const [url, setUrl] = useState(null);
  useEffect(() => {
    let vigente = true;
    let creado = null;
    pedir('GET', `/api/cientifico/hallazgos/${hallazgoId}/evidencias/${evidencia.id}`, { como: 'blob' })
      .then((blob) => { if (vigente) { creado = URL.createObjectURL(blob); setUrl(creado); } })
      .catch(() => {});
    return () => { vigente = false; if (creado) URL.revokeObjectURL(creado); };
  }, [hallazgoId, evidencia.id]);
  if (!url) return <div className="evidencia__marco" aria-hidden="true" />;
  return evidencia.tipo === 'FOTOGRAFIA'
    ? <img className="evidencia__marco" src={url} alt={`Evidencia: ${evidencia.nombre}`} />
    : <audio className="evidencia__audio" controls src={url} aria-label={`Audio: ${evidencia.nombre}`} />;
}

function Evidencias({ hallazgoId, puedeSubir, nombreCientifico }) {
  const [lista, setLista] = useState(null);
  const [error, setError] = useState(null);
  const [subiendo, setSubiendo] = useState(false);
  const cargar = useCallback(() => {
    api.get(`/api/cientifico/hallazgos/${hallazgoId}/evidencias`).then(setLista).catch(setError);
  }, [hallazgoId]);
  useEffect(cargar, [cargar]);

  // D65: el investigador decide qué foto puede salir en el portal público; por defecto ninguna.
  async function marcarPublica(evidencia, publica) {
    setError(null);
    try {
      const actualizada = await api.put(`/api/cientifico/hallazgos/${hallazgoId}/evidencias/${evidencia.id}/publica`, { publica });
      setLista((l) => l.map((e) => (e.id === actualizada.id ? actualizada : e)));
    } catch (falla) {
      setError(falla);
    }
  }

  async function subir(archivos) {
    setSubiendo(true);
    setError(null);
    try {
      for (const archivo of archivos) {
        const formulario = new FormData();
        formulario.append('archivo', archivo);
        await api.subir(`/api/cientifico/hallazgos/${hallazgoId}/evidencias`, formulario);
      }
    } catch (e) {
      setError(e);
    } finally {
      setSubiendo(false);
      cargar();
    }
  }

  return (
    <div className="tarjeta">
      <div className="acciones" style={{ justifyContent: 'space-between', marginBottom: 12 }}>
        <p className="tarjeta__titulo">Evidencias</p>
        {puedeSubir && (
          <label className="boton boton--fantasma boton--chico" style={{ cursor: 'pointer' }}>
            <Upload className="icono" aria-hidden="true" />{subiendo ? 'Subiendo…' : 'Agregar archivos'}
            <input type="file" multiple className="solo-lectores" accept=".jpg,.jpeg,.png,.wav,.mp3,.ogg,.flac,image/jpeg,image/png,audio/*"
              disabled={subiendo} onChange={(e) => { subir(Array.from(e.target.files)); e.target.value = ''; }} />
          </label>
        )}
      </div>
      <AvisoError error={error} />
      {lista === null && !error && <Cargando texto="Cargando evidencias…" />}
      {lista?.length === 0 && <p className="texto-suave">Este hallazgo todavía no tiene fotos ni audios.</p>}
      {puedeSubir && lista?.some((e) => e.tipo === 'FOTOGRAFIA') && (
        <p className="texto-suave" style={{ fontSize: 12.5, margin: '0 0 12px' }}>
          Marca «Mostrar en el portal» en la foto que quieras publicar en la ficha pública de <i lang="la">{nombreCientifico}</i>.
          Ya no lleva la ubicación GPS, pero revisa que la imagen no muestre el lugar exacto.
        </p>
      )}
      {lista?.length > 0 && (
        <ul className="evidencias">
          {lista.map((e) => (
            <li key={e.id} className="evidencia">
              <VistaEvidencia hallazgoId={hallazgoId} evidencia={e} />
              {e.publica && <span className="evidencia__sello chip chip--musgo"><Globe className="icono" aria-hidden="true" />Pública</span>}
              <div className="evidencia__pie">
                <span title={e.nombre}>{e.nombre}</span>
                <span className="texto-suave">{formatearTamano(e.tamanoBytes)} · {e.subidoPor ?? '—'}</span>
                <button type="button" className="enlace" onClick={() => descargar(`/api/cientifico/hallazgos/${hallazgoId}/evidencias/${e.id}`, e.nombre)}
                  style={{ display: 'inline-flex', gap: 4, alignItems: 'center', justifySelf: 'start' }}><Download className="icono" aria-hidden="true" />Descargar</button>
                {puedeSubir && e.tipo === 'FOTOGRAFIA' && (
                  <label className="evidencia__publica">
                    <input type="checkbox" checked={e.publica} onChange={(ev) => marcarPublica(e, ev.target.checked)} />
                    Mostrar en el portal
                  </label>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** HU-03: reclasificar exige justificación; los campos vacíos no se tocan. */
function Reclasificar({ hallazgo, alTerminar }) {
  const [datos, setDatos] = useState({
    nombreCientifico: hallazgo.nombreCientifico, nombreComun: hallazgo.nombreComun ?? '', familia: hallazgo.familia,
    tipoEspecimen: hallazgo.tipoEspecimen ?? '', fechaRecoleccion: hallazgo.fechaRecoleccion, departamento: hallazgo.departamento,
    categoriaUicn: hallazgo.categoriaUicn ?? 'NE', descripcion: hallazgo.descripcion ?? '', justificacion: '',
  });
  const [plantilla, setPlantilla] = useState(null);
  const [atributos, setAtributos] = useState(hallazgo.atributos ?? {});
  const [errores, setErrores] = useState({});
  const [error, setError] = useState(null);
  const [guardando, setGuardando] = useState(false);
  const cambiar = (c) => (e) => setDatos((d) => ({ ...d, [c]: e.target.value }));

  useEffect(() => {
    api.get(`/api/cientifico/fichas/${encodeURIComponent(hallazgo.nombreCientifico)}/atributos`).then(setPlantilla).catch(() => setPlantilla(null));
  }, [hallazgo.nombreCientifico]);

  async function guardar(e) {
    e.preventDefault();
    const { resultado, errores: deAtributos } = prepararAtributos(plantilla?.atributos, atributos);
    const nuevos = { ...deAtributos };
    if (!datos.justificacion.trim()) nuevos.justificacion = 'La justificación científica es obligatoria';
    setErrores(nuevos);
    if (Object.keys(nuevos).length) { setError(new Error('Revisa los campos marcados.')); return; }
    setGuardando(true);
    setError(null);
    try {
      await api.put(`/api/cientifico/hallazgos/${hallazgo.id}`, {
        ...datos, nombreComun: datos.nombreComun || null, descripcion: datos.descripcion || null,
        atributos: plantilla ? resultado : hallazgo.atributos, justificacion: datos.justificacion.trim(),
      });
      alTerminar(true);
    } catch (falla) {
      setError(falla);
      setGuardando(false);
    }
  }

  return (
    <form className="tarjeta formulario" onSubmit={guardar} noValidate>
      <p className="tarjeta__titulo">Reclasificar</p>
      <p className="texto-suave" style={{ margin: 0 }}>Se crea la versión {hallazgo.version + 1}; la anterior queda intacta en el historial.</p>
      <AvisoError error={error} titulo="No se reclasificó" />
      <div className="campos">
        <Campo etiqueta="Nombre científico" obligatorio>{(p) => <input {...p} value={datos.nombreCientifico} onChange={cambiar('nombreCientifico')} lang="la" />}</Campo>
        <Campo etiqueta="Nombre común">{(p) => <input {...p} value={datos.nombreComun} onChange={cambiar('nombreComun')} />}</Campo>
        <Campo etiqueta="Familia" obligatorio>{(p) => <input {...p} value={datos.familia} onChange={cambiar('familia')} />}</Campo>
        <Campo etiqueta="Tipo de espécimen" obligatorio>{(p) => <input {...p} value={datos.tipoEspecimen} onChange={cambiar('tipoEspecimen')} />}</Campo>
        <Campo etiqueta="Categoría UICN">
          {(p) => <select {...p} value={datos.categoriaUicn} onChange={cambiar('categoriaUicn')}>{CATEGORIAS_UICN.map(([v, t]) => <option key={v} value={v}>{t}</option>)}</select>}
        </Campo>
        <Campo etiqueta="Departamento" obligatorio>
          {(p) => <select {...p} value={datos.departamento} onChange={cambiar('departamento')}>{DEPARTAMENTOS.map((d) => <option key={d.codigo} value={d.nombre}>{d.nombre}</option>)}</select>}
        </Campo>
      </div>
      {plantilla && <CamposAtributos atributos={plantilla.atributos} valores={atributos} errores={errores}
        alCambiar={(clave, valor) => setAtributos((a) => ({ ...a, [clave]: valor }))} />}
      <Campo etiqueta="Justificación científica" obligatorio error={errores.justificacion} ayuda="Por ejemplo: resultado del análisis genético del laboratorio X">
        {(p) => <textarea {...p} value={datos.justificacion} onChange={cambiar('justificacion')} maxLength={1000} />}
      </Campo>
      <div className="acciones">
        <button type="submit" className="boton" disabled={guardando}>{guardando ? 'Guardando…' : 'Guardar nueva versión'}</button>
        <button type="button" className="boton boton--fantasma" onClick={() => alTerminar(false)} disabled={guardando}>Cancelar</button>
      </div>
    </form>
  );
}

export default function DetalleHallazgo() {
  const { id } = useParams();
  const { roles } = useSesion();
  const { state } = useLocation();
  const [hallazgo, setHallazgo] = useState(null);
  const [error, setError] = useState(null);
  const [editando, setEditando] = useState(false);
  const [mensaje, setMensaje] = useState(state?.creado ? 'Hallazgo registrado.' : null);
  const [etiquetas, setEtiquetas] = useState({});

  // Las etiquetas y unidades de los atributos salen de la plantilla de la especie (HU-02).
  useEffect(() => {
    if (!hallazgo) return;
    api.get(`/api/cientifico/fichas/${encodeURIComponent(hallazgo.nombreCientifico)}/atributos`)
      .then((p) => setEtiquetas(Object.fromEntries(p.atributos.map((a) => [a.clave, a]))))
      .catch(() => setEtiquetas({}));
  }, [hallazgo]);

  const cargar = useCallback(() => {
    setError(null);
    api.get(`/api/cientifico/hallazgos/${encodeURIComponent(id)}`).then(setHallazgo).catch(setError);
  }, [id]);
  useEffect(cargar, [cargar]);

  if (error?.status === 404) {
    return <section className="pagina"><Vacio titulo="Este hallazgo no existe"><Link className="boton" to="/buscar">Volver a la búsqueda</Link></Vacio></section>;
  }
  return (
    <section className="pagina">
      <CabeceraPagina pestana={hallazgo?.nombreCientifico ?? 'Hallazgo'} eyebrow={hallazgo ? `Hallazgo · versión ${hallazgo.version}` : 'Hallazgo'}
        titulo={hallazgo ? <i lang="la">{hallazgo.nombreCientifico}</i> : 'Cargando…'}
        descripcion={hallazgo?.nombreComun}>
        {hallazgo && puede(roles, 'verHistorial') && <Link className="boton boton--fantasma" to={`/hallazgos/${id}/historial`}><History className="icono" aria-hidden="true" />Historial y versiones</Link>}
        {hallazgo && puede(roles, 'verMapa') && <Link className="boton boton--fantasma" to={`/mapa?hallazgo=${id}`}><IconoMapa className="icono" aria-hidden="true" />Ver en el mapa</Link>}
        {hallazgo && puede(roles, 'reclasificar') && !editando && <button type="button" className="boton" onClick={() => setEditando(true)}><PenLine className="icono" aria-hidden="true" />Reclasificar</button>}
      </CabeceraPagina>
      {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
      {state?.fallidos?.length > 0 && <Aviso tipo="aviso">No se subieron: {state.fallidos.join('; ')}</Aviso>}
      <AvisoError error={error} />
      {!hallazgo && !error && <Cargando />}
      {hallazgo && (
        <div className="rejilla rejilla--2" style={{ alignItems: 'start' }}>
          <div>
            {editando ? (
              <Reclasificar hallazgo={hallazgo} alTerminar={(ok) => { setEditando(false); if (ok) { setMensaje('Nueva versión guardada.'); cargar(); } }} />
            ) : (
              <div className="tarjeta">
                <p className="tarjeta__titulo">Ficha</p>
                <dl className="ficha">
                  <dt>Estado</dt><dd><ChipEstado estado={hallazgo.estadoConservacion} /> <span className="texto-suave">UICN {hallazgo.categoriaUicn}</span></dd>
                  <dt>Familia</dt><dd>{hallazgo.familia}</dd>
                  <dt>Espécimen</dt><dd>{hallazgo.tipoEspecimen ?? '—'}</dd>
                  <dt>Recolección</dt><dd>{formatearFecha(hallazgo.fechaRecoleccion)}</dd>
                  <dt>Departamento</dt><dd>{hallazgo.departamento}</dd>
                  <dt>Investigador</dt><dd>{hallazgo.investigador}</dd>
                  {hallazgo.descripcion && <><dt>Descripción</dt><dd>{hallazgo.descripcion}</dd></>}
                  {Object.entries(hallazgo.atributos ?? {}).map(([k, v]) => (
                    <Fragment key={k}>
                      <dt>{etiquetas[k]?.etiqueta ?? k.replace(/_/g, ' ')}</dt>
                      <dd>{typeof v === 'boolean' ? (v ? 'Sí' : 'No') : String(v)}{etiquetas[k]?.unidad ? ` ${etiquetas[k].unidad}` : ''}</dd>
                    </Fragment>
                  ))}
                </dl>
                <p className="texto-suave" style={{ fontSize: 12, marginBottom: 0 }}>Las coordenadas no se muestran aquí: viven cifradas en el módulo de ubicación.</p>
              </div>
            )}
          </div>
          <Evidencias hallazgoId={hallazgo.id} puedeSubir={puede(roles, 'registrarHallazgo')} nombreCientifico={hallazgo.nombreCientifico} />
        </div>
      )}
    </section>
  );
}
