import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/cliente.js';
import { ROLES } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import { Aviso, AvisoError, CabeceraPagina, Campo, Cargando, Paginacion, Vacio } from '../componentes/ui.jsx';

const VACIO = { usuario: '', email: '', nombre: '', apellido: '', rol: 'investigador_campo' };

function Crear({ alCrear }) {
  const [datos, setDatos] = useState(VACIO);
  const [error, setError] = useState(null);
  const [guardando, setGuardando] = useState(false);
  const [creado, setCreado] = useState(null);
  const cambiar = (c) => (e) => setDatos((d) => ({ ...d, [c]: e.target.value }));
  async function enviar(e) {
    e.preventDefault();
    setGuardando(true); setError(null);
    try {
      const r = await api.post('/api/gateway/usuarios', datos);
      setCreado({ ...r, usuario: datos.usuario });
      setDatos(VACIO);
      alCrear();
    } catch (falla) { setError(falla); } finally { setGuardando(false); }
  }
  return (
    <form className="tarjeta formulario" onSubmit={enviar}>
      <p className="tarjeta__titulo">Nuevo usuario</p>
      {creado && (
        <div className="tarjeta tarjeta--tinta muesca" role="status">
          <p className="eyebrow">Solo se muestra esta vez</p>
          <p style={{ margin: '8px 0' }}>Contraseña temporal de <strong>{creado.usuario}</strong>:</p>
          <p className="mono secreto">{creado.contrasenaTemporal}</p>
          <div className="acciones">
            <button type="button" className="boton boton--claro boton--chico" onClick={() => navigator.clipboard?.writeText(creado.contrasenaTemporal)}>Copiar</button>
            <button type="button" className="boton boton--fantasma boton--chico" style={{ color: '#fff', borderColor: '#fff' }} onClick={() => setCreado(null)}>Listo</button>
          </div>
          <p className="texto-suave" style={{ fontSize: 12, margin: '10px 0 0' }}>Entrégala por un canal privado. Keycloak pedirá cambiarla en el primer inicio de sesión.</p>
        </div>
      )}
      <AvisoError error={error} titulo="No se creó" />
      <div className="campos">
        <Campo etiqueta="Usuario" obligatorio ayuda="No se puede cambiar después">{(p) => <input {...p} required value={datos.usuario} onChange={cambiar('usuario')} autoComplete="off" />}</Campo>
        <Campo etiqueta="Correo" obligatorio>{(p) => <input {...p} type="email" required value={datos.email} onChange={cambiar('email')} autoComplete="off" />}</Campo>
        <Campo etiqueta="Nombre" obligatorio>{(p) => <input {...p} required value={datos.nombre} onChange={cambiar('nombre')} />}</Campo>
        <Campo etiqueta="Apellido" obligatorio>{(p) => <input {...p} required value={datos.apellido} onChange={cambiar('apellido')} />}</Campo>
        <Campo etiqueta="Rol" obligatorio ayuda="Exactamente uno (HU-10)">{(p) => <select {...p} value={datos.rol} onChange={cambiar('rol')}>{Object.entries(ROLES).map(([v, t]) => <option key={v} value={v}>{t}</option>)}</select>}</Campo>
      </div>
      <div><button type="submit" className="boton" disabled={guardando}>{guardando ? 'Creando…' : 'Crear usuario'}</button></div>
    </form>
  );
}

function Fila({ u, esYo, alCambiar }) {
  const [error, setError] = useState(null);
  const [ocupado, setOcupado] = useState(false);
  const rolActual = (u.roles ?? []).find((r) => ROLES[r]) ?? '';
  async function hacer(promesa, mensaje) {
    setOcupado(true); setError(null);
    try { await promesa; alCambiar(mensaje); } catch (falla) { setError(falla); } finally { setOcupado(false); }
  }
  return (
    <tr style={u.activo ? undefined : { opacity: 0.6 }}>
      <td><strong>{u.nombre} {u.apellido}</strong><div className="texto-suave" style={{ fontSize: 12 }}>{u.usuario} · {u.email}</div></td>
      <td>
        <select aria-label={`Rol de ${u.usuario}`} className="celda-entrada" value={rolActual} disabled={esYo || ocupado}
          onChange={(e) => (e.target.value
            ? hacer(api.put(`/api/gateway/usuarios/${u.id}/rol`, { rol: e.target.value }), `${u.usuario}: rol cambiado. Sus sesiones abiertas se cerraron.`)
            : hacer(api.borrar(`/api/gateway/usuarios/${u.id}/rol`), `${u.usuario} quedó sin rol: ya no entra a los módulos internos.`))}>
          <option value="">Sin rol</option>
          {Object.entries(ROLES).map(([v, t]) => <option key={v} value={v}>{t}</option>)}
        </select>
        {error && <p className="campo__error" role="alert">{error.message}</p>}
      </td>
      <td>{u.activo ? <span className="chip chip--musgo">Activo</span> : <span className="chip chip--neutro">Inactivo</span>}</td>
      <td>
        {esYo ? <span className="texto-suave" style={{ fontSize: 12 }}>Tu cuenta</span> : (
          <button type="button" className="boton boton--fantasma boton--chico" disabled={ocupado}
            onClick={() => hacer(api.put(`/api/gateway/usuarios/${u.id}/estado`, { activo: !u.activo }), `${u.usuario}: ${u.activo ? 'desactivado' : 'activado'}.`)}>
            {u.activo ? 'Desactivar' : 'Activar'}
          </button>
        )}
      </td>
    </tr>
  );
}

/** HU-10, RF17 y RF19: usuarios internos con exactamente un rol; los cambios aplican desde la siguiente petición. */
export default function Usuarios() {
  const { usuario } = useSesion();
  const [busqueda, setBusqueda] = useState('');
  const [consulta, setConsulta] = useState({ busqueda: '', page: 0 });
  const [pagina, setPagina] = useState(null);
  const [error, setError] = useState(null);
  const [mensaje, setMensaje] = useState(null);

  const cargar = useCallback(() => {
    const q = new URLSearchParams({ page: consulta.page, size: 15 });
    if (consulta.busqueda) q.set('busqueda', consulta.busqueda);
    api.get(`/api/gateway/usuarios?${q}`).then(setPagina).catch(setError);
  }, [consulta]);
  useEffect(cargar, [cargar]);

  return (
    <section className="pagina">
      <CabeceraPagina pestana="Usuarios y roles" eyebrow="Administración · HU-10" titulo={<>Usuarios y <em>roles</em></>}
        descripcion="Un cambio de rol se aplica desde la siguiente petición del usuario: sus tokens anteriores dejan de servir y debe volver a entrar." />
      {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
      <div className="rejilla" style={{ gridTemplateColumns: 'minmax(0, 2fr) minmax(280px, 1fr)', alignItems: 'start' }}>
        <div className="tarjeta">
          <form className="acciones" style={{ marginBottom: 16, alignItems: 'flex-end' }} onSubmit={(e) => { e.preventDefault(); setConsulta({ busqueda: busqueda.trim(), page: 0 }); }}>
            <div className="campo" style={{ flex: '1 1 220px' }}>
              <label htmlFor="buscar-usuario">Buscar</label>
              <input id="buscar-usuario" value={busqueda} onChange={(e) => setBusqueda(e.target.value)} placeholder="Usuario, nombre o correo" />
            </div>
            <button type="submit" className="boton boton--fantasma">Buscar</button>
          </form>
          <AvisoError error={error} />
          {!pagina && !error && <Cargando />}
          {pagina?.content.length === 0 && <Vacio titulo="Nadie coincide con la búsqueda" />}
          {pagina?.content.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead><tr><th>Persona</th><th>Rol</th><th>Estado</th><th><span className="solo-lectores">Acción</span></th></tr></thead>
                  <tbody>
                    {pagina.content.map((u) => <Fila key={u.id} u={u} esYo={u.usuario === usuario} alCambiar={(m) => { setMensaje(m); cargar(); }} />)}
                  </tbody>
                </table>
              </div>
              <Paginacion pagina={pagina.page} totalPaginas={pagina.totalPaginas} total={pagina.total} alCambiar={(page) => setConsulta((c) => ({ ...c, page }))} />
            </>
          )}
        </div>
        <Crear alCrear={() => { setMensaje('Usuario creado.'); cargar(); }} />
      </div>
    </section>
  );
}
