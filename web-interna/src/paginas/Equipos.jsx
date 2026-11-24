import { Fragment, useCallback, useEffect, useState } from 'react';
import { Ban, Binoculars, Check, History, PackageCheck, Plus, Undo2, Wrench } from 'lucide-react';
import { api } from '../api/cliente.js';
import { Aviso, AvisoError, CabeceraPagina, Campo, Cargando, ChipEstado, formatearFecha, Paginacion, Vacio } from '../componentes/ui.jsx';

const CATEGORIAS = ['Dron', 'Cámara trampa', 'Hidrófono', 'Sensor', 'GPS', 'Otro'];
const ESTADOS = [['', 'Todos'], ['DISPONIBLE', 'Disponible'], ['ASIGNADO', 'En expedición'], ['MANTENIMIENTO', 'Mantenimiento'], ['BAJA', 'De baja']];

function useInvestigadores() {
  const [lista, setLista] = useState([]);
  useEffect(() => {
    api.get('/api/gateway/usuarios?page=0&size=50')
      .then((p) => setLista((p.content ?? []).filter((u) => u.activo && (u.roles ?? []).includes('investigador_campo'))))
      .catch(() => setLista([]));
  }, []);
  return lista;
}

const VACIO = { codigoInventario: '', nombre: '', categoria: CATEGORIAS[0], descripcion: '', custodioId: '' };

/** HU-04: todo equipo nace entregado a un investigador de campo activo (el servicio exige `custodioId`). */
function Registrar({ investigadores, alCrear }) {
  const [datos, setDatos] = useState(VACIO);
  const [error, setError] = useState(null);
  const [guardando, setGuardando] = useState(false);
  const cambiar = (c) => (e) => setDatos((d) => ({ ...d, [c]: e.target.value }));
  async function enviar(e) {
    e.preventDefault();
    setGuardando(true); setError(null);
    try {
      const creado = await api.post('/api/equipos', { ...datos, descripcion: datos.descripcion.trim() || null });
      setDatos(VACIO);
      alCrear(creado);
    } catch (falla) { setError(falla); } finally { setGuardando(false); }
  }
  return (
    <form className="tarjeta formulario" onSubmit={enviar}>
      <p className="tarjeta__titulo">Registrar equipo</p>
      <AvisoError error={error} titulo="No se registró" />
      <div className="campos">
        <Campo etiqueta="Código de inventario" obligatorio ayuda="Único, p. ej. DRN-0042">{(p) => <input {...p} required maxLength={40} value={datos.codigoInventario} onChange={cambiar('codigoInventario')} />}</Campo>
        <Campo etiqueta="Nombre" obligatorio>{(p) => <input {...p} required maxLength={120} value={datos.nombre} onChange={cambiar('nombre')} />}</Campo>
        <Campo etiqueta="Categoría" obligatorio>{(p) => <select {...p} value={datos.categoria} onChange={cambiar('categoria')}>{CATEGORIAS.map((c) => <option key={c}>{c}</option>)}</select>}</Campo>
      </div>
      <Campo etiqueta="Custodio" obligatorio ayuda={investigadores.length
        ? 'Investigador de campo activo que se lleva el equipo'
        : 'No hay investigadores de campo activos: crea uno en Usuarios y roles'}>
        {(p) => (
          <select {...p} required value={datos.custodioId} onChange={cambiar('custodioId')}>
            <option value="">Elige quién lo recibe</option>
            {investigadores.map((u) => <option key={u.id} value={u.id}>{u.nombre} {u.apellido} ({u.usuario})</option>)}
          </select>
        )}
      </Campo>
      <Campo etiqueta="Descripción">{(p) => <textarea {...p} maxLength={500} value={datos.descripcion} onChange={cambiar('descripcion')} />}</Campo>
      <div><button type="submit" className="boton" disabled={guardando}><Plus className="icono" aria-hidden="true" />{guardando ? 'Guardando…' : 'Registrar equipo'}</button></div>
    </form>
  );
}

function Acciones({ equipo, investigadores, alCambiar }) {
  const [custodio, setCustodio] = useState('');
  const [error, setError] = useState(null);
  const [ocupado, setOcupado] = useState(false);
  async function hacer(promesa) {
    setOcupado(true); setError(null);
    try { alCambiar(await promesa); setCustodio(''); } catch (falla) { setError(falla); } finally { setOcupado(false); }
  }
  return (
    <div style={{ display: 'grid', gap: 8 }}>
      {/* Solo un equipo disponible se asigna: uno en expedición se devuelve primero (sin asignaciones simultáneas) */}
      {equipo.estado === 'DISPONIBLE' && (
        <div className="acciones">
          <select aria-label={`Custodio para ${equipo.nombre}`} value={custodio} onChange={(e) => setCustodio(e.target.value)} className="celda-entrada">
            <option value="">Asignar a…</option>
            {investigadores.map((u) => <option key={u.id} value={u.id}>{u.nombre} {u.apellido} ({u.usuario})</option>)}
          </select>
          <button type="button" className="boton boton--chico" disabled={!custodio || ocupado}
            onClick={() => hacer(api.put(`/api/equipos/${equipo.id}/asignacion`, { custodioId: custodio }))}><Check className="icono" aria-hidden="true" />Asignar</button>
        </div>
      )}
      <div className="acciones">
        {equipo.estado !== 'DISPONIBLE' && equipo.estado !== 'BAJA' && (
          <button type="button" className="boton boton--fantasma boton--chico" disabled={ocupado}
            onClick={() => hacer(api.put(`/api/equipos/${equipo.id}/estado`, { estado: 'DISPONIBLE' }))}>{equipo.estado === 'ASIGNADO' ? <><Undo2 className="icono" aria-hidden="true" />Devolver</> : <><PackageCheck className="icono" aria-hidden="true" />Disponible</>}</button>
        )}
        {equipo.estado !== 'MANTENIMIENTO' && equipo.estado !== 'BAJA' && (
          <button type="button" className="boton boton--fantasma boton--chico" disabled={ocupado}
            onClick={() => hacer(api.put(`/api/equipos/${equipo.id}/estado`, { estado: 'MANTENIMIENTO' }))}><Wrench className="icono" aria-hidden="true" />Mantenimiento</button>
        )}
        {equipo.estado !== 'BAJA' && (
          <button type="button" className="boton boton--fantasma boton--chico" disabled={ocupado}
            onClick={() => { if (window.confirm(`¿Dar de baja ${equipo.nombre}? No se podrá volver a asignar.`)) hacer(api.put(`/api/equipos/${equipo.id}/estado`, { estado: 'BAJA' })); }}><Ban className="icono" aria-hidden="true" />Dar de baja</button>
        )}
      </div>
      {error && <p className="campo__error" role="alert">{error.message}</p>}
    </div>
  );
}

const ACCIONES = {
  ALTA_ASIGNADA: 'Alta y entrega', ASIGNACION: 'Asignado', CAMBIO_ESTADO: 'Cambio de estado', BAJA_DEFINITIVA: 'Baja definitiva',
};

/** Historial de custodia de un equipo (GET /api/equipos/{id}/historial): quién lo tuvo y cada cambio de estado. */
function HistorialEquipo({ equipo }) {
  const [lista, setLista] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    api.get(`/api/equipos/${equipo.id}/historial`).then(setLista).catch(setError);
  }, [equipo.id]);
  return (
    <div className="historial-equipo">
      <AvisoError error={error} titulo="No se pudo cargar el historial" />
      {!lista && !error && <Cargando texto="Cargando historial…" />}
      {lista?.length === 0 && <p className="texto-suave">Sin movimientos registrados.</p>}
      {lista?.length > 0 && (
        <ol className="historial-equipo__lista" aria-label={`Historial de custodia de ${equipo.nombre}`}>
          {lista.map((h) => (
            <li key={h.id}>
              <span className="historial-equipo__fecha">{formatearFecha(h.registradoEn, true)}</span>
              <strong>{ACCIONES[h.accion] ?? h.accion.replace(/_/g, ' ').toLowerCase()}</strong>
              {h.estadoAnterior && h.estadoNuevo && h.estadoAnterior !== h.estadoNuevo && (
                <span> · <ChipEstado estado={h.estadoAnterior} /> → <ChipEstado estado={h.estadoNuevo} /></span>
              )}
              {h.custodioUsuario && <span> · {h.custodioUsuario}</span>}
              {h.observacion && <span className="texto-suave"> · {h.observacion}</span>}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

const RESUMEN = [
  ['DISPONIBLE', 'Disponibles', PackageCheck, 'musgo'],
  ['ASIGNADO', 'En expedición', Binoculars, 'pizarra'],
  ['MANTENIMIENTO', 'En mantenimiento', Wrench, 'ocre'],
  ['BAJA', 'De baja', Ban, 'neutro'],
];

/** Cuántos equipos hay en cada estado (una petición de tamaño 1 por estado; se recalcula tras cada cambio). */
function Indicadores({ version }) {
  const [conteos, setConteos] = useState({});
  useEffect(() => {
    Promise.all(RESUMEN.map(([estado]) => api.get(`/api/equipos?estado=${estado}&page=0&size=1`)
      .then((p) => [estado, p.total]).catch(() => [estado, null])))
      .then((pares) => setConteos(Object.fromEntries(pares)));
  }, [version]);
  return (
    <div className="indicadores">
      {RESUMEN.map(([estado, texto, Icono, tono]) => (
        <div key={estado} className="indicador">
          <span className={`indicador__icono tono--${tono}`}><Icono className="icono" aria-hidden="true" /></span>
          <div><p className="indicador__valor">{conteos[estado] ?? '—'}</p><p className="indicador__texto">{texto}</p></div>
        </div>
      ))}
    </div>
  );
}

/** HU-04: inventario de equipos de campo, cada uno con su custodio investigador. */
export default function Equipos() {
  const investigadores = useInvestigadores();
  const [filtros, setFiltros] = useState({ estado: '', custodioId: '', page: 0 });
  const [pagina, setPagina] = useState(null);
  const [error, setError] = useState(null);
  const [mensaje, setMensaje] = useState(null);
  const [historialDe, setHistorialDe] = useState(null);

  const cargar = useCallback(() => {
    const q = new URLSearchParams({ page: filtros.page, size: 15 });
    if (filtros.estado) q.set('estado', filtros.estado);
    if (filtros.custodioId) q.set('custodioId', filtros.custodioId);
    setError(null);
    api.get(`/api/equipos?${q}`).then(setPagina).catch(setError);
  }, [filtros]);
  useEffect(cargar, [cargar]);

  return (
    <section className="pagina">
      <CabeceraPagina pestana="Equipos de campo" eyebrow="Inventario · HU-04" titulo={<>Equipos de <em>campo</em></>} pintura="auto"
        descripcion="Drones, cámaras trampa, hidrófonos y sensores son activos del Estado: cada uno sale a campo con un investigador activo como custodio." />
      {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
      <Indicadores version={pagina} />
      <div className="rejilla" style={{ gridTemplateColumns: 'minmax(0, 2fr) minmax(280px, 1fr)', alignItems: 'start' }}>
        <div className="tarjeta">
          <div className="campos" style={{ marginBottom: 16 }}>
            <Campo etiqueta="Estado">{(p) => <select {...p} value={filtros.estado} onChange={(e) => setFiltros((f) => ({ ...f, estado: e.target.value, page: 0 }))}>{ESTADOS.map(([v, t]) => <option key={v} value={v}>{t}</option>)}</select>}</Campo>
            <Campo etiqueta="Custodio" ayuda="Qué tiene cada investigador ahora">{(p) => (
              <select {...p} value={filtros.custodioId} onChange={(e) => setFiltros((f) => ({ ...f, custodioId: e.target.value, page: 0 }))}>
                <option value="">Todos</option>
                {investigadores.map((u) => <option key={u.id} value={u.id}>{u.usuario}</option>)}
              </select>
            )}</Campo>
          </div>
          <AvisoError error={error} />
          {!pagina && !error && <Cargando />}
          {pagina?.content.length === 0 && <Vacio titulo="Sin equipos con estos filtros" />}
          {pagina?.content.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead><tr><th>Equipo</th><th>Estado</th><th>Custodio</th><th>Acciones</th></tr></thead>
                  <tbody>
                    {pagina.content.map((e) => (
                      <Fragment key={e.id}>
                      <tr>
                        <td><strong>{e.nombre}</strong><div className="mono texto-suave">{e.codigoInventario} · {e.categoria}</div></td>
                        <td><ChipEstado estado={e.estado} /></td>
                        <td>{e.custodioUsuario ?? '—'}{e.asignadoEn && <div className="texto-suave" style={{ fontSize: 12 }}>desde {formatearFecha(e.asignadoEn)}</div>}</td>
                        <td>
                          <Acciones equipo={e} investigadores={investigadores} alCambiar={(nuevo) => { setMensaje(`${nuevo.nombre}: ${nuevo.estado === 'ASIGNADO' ? `asignado a ${nuevo.custodioUsuario}` : 'estado actualizado'}.`); cargar(); }} />
                          <button type="button" className="enlace historial-equipo__boton" aria-expanded={historialDe === e.id}
                            onClick={() => setHistorialDe((h) => (h === e.id ? null : e.id))}>
                            <History className="icono" aria-hidden="true" />{historialDe === e.id ? 'Ocultar historial' : 'Historial'}
                          </button>
                        </td>
                      </tr>
                      {historialDe === e.id && (
                        <tr className="historial-equipo__fila"><td colSpan={4}><HistorialEquipo key={e.actualizadoEn ?? e.id} equipo={e} /></td></tr>
                      )}
                      </Fragment>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paginacion pagina={pagina.page} totalPaginas={pagina.totalPaginas} total={pagina.total} alCambiar={(page) => setFiltros((f) => ({ ...f, page }))} />
            </>
          )}
        </div>
        <Registrar investigadores={investigadores} alCrear={(e) => { setMensaje(`${e.nombre} registrado y entregado a ${e.custodioUsuario ?? 'su custodio'}.`); cargar(); }} />
      </div>
    </section>
  );
}
