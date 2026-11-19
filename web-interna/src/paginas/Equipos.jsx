import { useCallback, useEffect, useState } from 'react';
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

function Registrar({ alCrear }) {
  const [datos, setDatos] = useState({ codigoInventario: '', nombre: '', categoria: CATEGORIAS[0], descripcion: '' });
  const [error, setError] = useState(null);
  const [guardando, setGuardando] = useState(false);
  const cambiar = (c) => (e) => setDatos((d) => ({ ...d, [c]: e.target.value }));
  async function enviar(e) {
    e.preventDefault();
    setGuardando(true); setError(null);
    try {
      const creado = await api.post('/api/equipos', { ...datos, descripcion: datos.descripcion.trim() || null });
      setDatos({ codigoInventario: '', nombre: '', categoria: CATEGORIAS[0], descripcion: '' });
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
      <Campo etiqueta="Descripción">{(p) => <textarea {...p} maxLength={500} value={datos.descripcion} onChange={cambiar('descripcion')} />}</Campo>
      <div><button type="submit" className="boton" disabled={guardando}>{guardando ? 'Guardando…' : 'Registrar equipo'}</button></div>
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
      {equipo.estado !== 'BAJA' && equipo.estado !== 'MANTENIMIENTO' && (
        <div className="acciones">
          <select aria-label={`Custodio para ${equipo.nombre}`} value={custodio} onChange={(e) => setCustodio(e.target.value)} className="celda-entrada">
            <option value="">{equipo.estado === 'ASIGNADO' ? 'Reasignar a…' : 'Asignar a…'}</option>
            {investigadores.map((u) => <option key={u.id} value={u.id}>{u.nombre} {u.apellido} ({u.usuario})</option>)}
          </select>
          <button type="button" className="boton boton--chico" disabled={!custodio || ocupado}
            onClick={() => hacer(api.put(`/api/equipos/${equipo.id}/asignacion`, { custodioId: custodio }))}>Asignar</button>
        </div>
      )}
      <div className="acciones">
        {equipo.estado !== 'DISPONIBLE' && equipo.estado !== 'BAJA' && (
          <button type="button" className="boton boton--fantasma boton--chico" disabled={ocupado}
            onClick={() => hacer(api.put(`/api/equipos/${equipo.id}/estado`, { estado: 'DISPONIBLE' }))}>{equipo.estado === 'ASIGNADO' ? 'Devolver' : 'Disponible'}</button>
        )}
        {equipo.estado !== 'MANTENIMIENTO' && equipo.estado !== 'BAJA' && (
          <button type="button" className="boton boton--fantasma boton--chico" disabled={ocupado}
            onClick={() => hacer(api.put(`/api/equipos/${equipo.id}/estado`, { estado: 'MANTENIMIENTO' }))}>Mantenimiento</button>
        )}
        {equipo.estado !== 'BAJA' && (
          <button type="button" className="boton boton--fantasma boton--chico" disabled={ocupado}
            onClick={() => { if (window.confirm(`¿Dar de baja ${equipo.nombre}? No se podrá volver a asignar.`)) hacer(api.put(`/api/equipos/${equipo.id}/estado`, { estado: 'BAJA' })); }}>Dar de baja</button>
        )}
      </div>
      {error && <p className="campo__error" role="alert">{error.message}</p>}
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
      <CabeceraPagina pestana="Equipos de campo" eyebrow="Inventario · HU-04" titulo={<>Equipos de <em>campo</em></>}
        descripcion="Drones, cámaras trampa, hidrófonos y sensores son activos del Estado: cada uno sale a campo con un investigador activo como custodio." />
      {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
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
                      <tr key={e.id}>
                        <td><strong>{e.nombre}</strong><div className="mono texto-suave">{e.codigoInventario} · {e.categoria}</div></td>
                        <td><ChipEstado estado={e.estado} /></td>
                        <td>{e.custodioUsuario ?? '—'}{e.asignadoEn && <div className="texto-suave" style={{ fontSize: 12 }}>desde {formatearFecha(e.asignadoEn)}</div>}</td>
                        <td><Acciones equipo={e} investigadores={investigadores} alCambiar={(nuevo) => { setMensaje(`${nuevo.nombre}: ${nuevo.estado === 'ASIGNADO' ? `asignado a ${nuevo.custodioUsuario}` : 'estado actualizado'}.`); cargar(); }} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paginacion pagina={pagina.page} totalPaginas={pagina.totalPaginas} total={pagina.total} alCambiar={(page) => setFiltros((f) => ({ ...f, page }))} />
            </>
          )}
        </div>
        <Registrar alCrear={(e) => { setMensaje(`${e.nombre} registrado.`); cargar(); }} />
      </div>
    </section>
  );
}
