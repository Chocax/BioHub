import { useCallback, useEffect, useMemo, useState } from 'react';
import { Ban, Check, CheckCircle2, Copy, Search, ShieldCheck, UserMinus, UserPlus, Users as IconoUsuarios, UserX } from 'lucide-react';
import { api } from '../api/cliente.js';
import { ROLES } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import { AvisoError, CabeceraPagina, Campo, Cargando, Paginacion, Vacio } from '../componentes/ui.jsx';

const VACIO = { usuario: '', email: '', nombre: '', apellido: '', rol: 'investigador_campo' };
const POR_PAGINA = 50;

// Color de cada rol: el avatar, la etiqueta y el filtro lo usan igual, así se reconoce de un vistazo.
export const TONOS_ROL = {
  investigador_campo: 'musgo', cientifico_alto_rango: 'pizarra', auditor_cientifico: 'ocre',
  analista_ambiental: 'terracota', administrador: 'tinta',
};

const rolDe = (u) => (u.roles ?? []).find((r) => ROLES[r]) ?? '';
const iniciales = (u) => `${u.nombre?.[0] ?? ''}${u.apellido?.[0] ?? ''}`.toUpperCase() || u.usuario?.[0]?.toUpperCase();

function Crear({ alCrear }) {
  const [datos, setDatos] = useState(VACIO);
  const [error, setError] = useState(null);
  const [guardando, setGuardando] = useState(false);
  const [creado, setCreado] = useState(null);
  const [copiado, setCopiado] = useState(false);
  const cambiar = (c) => (e) => setDatos((d) => ({ ...d, [c]: e.target.value }));
  async function enviar(e) {
    e.preventDefault();
    setGuardando(true); setError(null); setCopiado(false);
    try {
      const r = await api.post('/api/gateway/usuarios', datos);
      setCreado({ ...r, usuario: datos.usuario });
      setDatos(VACIO);
      alCrear(datos.usuario);
    } catch (falla) { setError(falla); } finally { setGuardando(false); }
  }
  return (
    <form className="tarjeta formulario usuarios__crear" onSubmit={enviar}>
      <p className="tarjeta__titulo titulo-icono"><UserPlus className="icono" aria-hidden="true" />Nuevo usuario</p>
      {creado && (
        <div className="tarjeta tarjeta--tinta muesca" role="status">
          <p className="eyebrow">Solo se muestra esta vez</p>
          <p style={{ margin: '8px 0' }}>Contraseña temporal de <strong>{creado.usuario}</strong>:</p>
          <p className="mono secreto">{creado.contrasenaTemporal}</p>
          <div className="acciones">
            <button type="button" className="boton boton--claro boton--chico"
              onClick={() => { navigator.clipboard?.writeText(creado.contrasenaTemporal); setCopiado(true); }}>
              {copiado ? <><Check className="icono" aria-hidden="true" />Copiada</> : <><Copy className="icono" aria-hidden="true" />Copiar</>}
            </button>
            <button type="button" className="boton boton--fantasma boton--chico boton--sobre-tinta" onClick={() => setCreado(null)}>Listo</button>
          </div>
          <p className="texto-suave" style={{ fontSize: 12, margin: '10px 0 0' }}>Entrégala por un canal privado. Keycloak pedirá cambiarla en el primer inicio de sesión.</p>
        </div>
      )}
      <AvisoError error={error} titulo="No se creó" />
      <Campo etiqueta="Usuario" obligatorio ayuda="No se puede cambiar después">{(p) => <input {...p} required value={datos.usuario} onChange={cambiar('usuario')} autoComplete="off" />}</Campo>
      <Campo etiqueta="Correo" obligatorio>{(p) => <input {...p} type="email" required value={datos.email} onChange={cambiar('email')} autoComplete="off" />}</Campo>
      <div className="campos">
        <Campo etiqueta="Nombre" obligatorio>{(p) => <input {...p} required value={datos.nombre} onChange={cambiar('nombre')} />}</Campo>
        <Campo etiqueta="Apellido" obligatorio>{(p) => <input {...p} required value={datos.apellido} onChange={cambiar('apellido')} />}</Campo>
      </div>
      <fieldset className="grupo">
        <legend>Rol</legend>
        <div className="selector-roles">
          {Object.entries(ROLES).map(([v, t]) => (
            <label key={v} className={`selector-roles__opcion tono-borde--${TONOS_ROL[v]}`} data-elegido={datos.rol === v}>
              <input type="radio" name="rol-nuevo" value={v} checked={datos.rol === v} onChange={cambiar('rol')} />
              <span className={`punto tono--${TONOS_ROL[v]}`} aria-hidden="true" />{t}
            </label>
          ))}
        </div>
        <p className="campo__ayuda" style={{ marginTop: 8 }}>Exactamente uno (HU-10).</p>
      </fieldset>
      <div><button type="submit" className="boton" disabled={guardando}><UserPlus className="icono" aria-hidden="true" />{guardando ? 'Creando…' : 'Crear usuario'}</button></div>
    </form>
  );
}

function Fila({ u, esYo, resaltada, alCambiar }) {
  const [error, setError] = useState(null);
  const [ocupado, setOcupado] = useState(false);
  const rolActual = rolDe(u);
  async function hacer(promesa, mensaje) {
    setOcupado(true); setError(null);
    try { await promesa; alCambiar(mensaje, u.usuario); } catch (falla) { setError(falla); } finally { setOcupado(false); }
  }
  function cambiarEstado() {
    if (u.activo && !window.confirm(`¿Desactivar a ${u.usuario}? No podrá entrar hasta que lo actives de nuevo.`)) return;
    hacer(api.put(`/api/gateway/usuarios/${u.id}/estado`, { activo: !u.activo }), `${u.usuario}: ${u.activo ? 'desactivado. Ya no puede entrar.' : 'activado de nuevo.'}`);
  }
  return (
    <tr className={`usuarios__fila${u.activo ? '' : ' usuarios__fila--inactiva'}${resaltada ? ' fila--cambiada' : ''}`}>
      <td>
        <div className="persona">
          <span className={`avatar avatar--grande tono--${TONOS_ROL[rolActual] ?? 'neutro'}`} aria-hidden="true">{iniciales(u)}</span>
          <div>
            <strong>{u.nombre} {u.apellido}</strong>{esYo && <span className="chip chip--neutro" style={{ marginLeft: 8 }}>Tú</span>}
            <div className="texto-suave usuarios__correo">{u.usuario} · {u.email}</div>
          </div>
        </div>
      </td>
      <td>
        <select aria-label={`Rol de ${u.usuario}`} className={`celda-entrada usuarios__rol tono-borde--${TONOS_ROL[rolActual] ?? 'neutro'}`}
          value={rolActual} disabled={esYo || ocupado}
          onChange={(e) => (e.target.value
            ? hacer(api.put(`/api/gateway/usuarios/${u.id}/rol`, { rol: e.target.value }), `${u.usuario} ahora es ${ROLES[e.target.value]}. Sus sesiones abiertas se cerraron.`)
            : hacer(api.borrar(`/api/gateway/usuarios/${u.id}/rol`), `${u.usuario} quedó sin rol: ya no entra a los módulos internos.`))}>
          <option value="">Sin rol</option>
          {Object.entries(ROLES).map(([v, t]) => <option key={v} value={v}>{t}</option>)}
        </select>
        {error && <p className="campo__error" role="alert">{error.message}</p>}
      </td>
      <td>
        {u.activo
          ? <span className="chip chip--musgo"><CheckCircle2 className="icono" aria-hidden="true" />Activo</span>
          : <span className="chip chip--terracota"><UserX className="icono" aria-hidden="true" />Inactivo</span>}
      </td>
      <td className="usuarios__accion">
        {esYo ? <span className="texto-suave" style={{ fontSize: 12.5 }}><ShieldCheck className="icono" aria-hidden="true" /> Tu cuenta</span> : (
          <button type="button" className={`boton boton--chico ${u.activo ? 'boton--peligro-suave' : 'boton--exito-suave'}`} disabled={ocupado} onClick={cambiarEstado}>
            {u.activo ? <><Ban className="icono" aria-hidden="true" />Desactivar</> : <><Check className="icono" aria-hidden="true" />Activar</>}
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
  const [resaltado, setResaltado] = useState(null);
  const [filtro, setFiltro] = useState('todos');

  const cargar = useCallback(() => {
    const q = new URLSearchParams({ page: consulta.page, size: POR_PAGINA });
    if (consulta.busqueda) q.set('busqueda', consulta.busqueda);
    api.get(`/api/gateway/usuarios?${q}`).then(setPagina).catch(setError);
  }, [consulta]);
  useEffect(cargar, [cargar]);

  // El aviso y el resaltado de la fila se van solos: confirman el cambio sin quedarse estorbando.
  useEffect(() => {
    if (!mensaje) return undefined;
    const t = window.setTimeout(() => { setMensaje(null); setResaltado(null); }, 6000);
    return () => window.clearTimeout(t);
  }, [mensaje]);

  const todos = useMemo(() => pagina?.content ?? [], [pagina]);
  const conteos = useMemo(() => ({
    total: todos.length,
    activos: todos.filter((u) => u.activo).length,
    inactivos: todos.filter((u) => !u.activo).length,
    sinRol: todos.filter((u) => !rolDe(u)).length,
    ...Object.fromEntries(Object.keys(ROLES).map((r) => [r, todos.filter((u) => rolDe(u) === r).length])),
  }), [todos]);
  const visibles = todos.filter((u) => {
    if (filtro === 'todos') return true;
    if (filtro === 'inactivos') return !u.activo;
    if (filtro === 'sin-rol') return !rolDe(u);
    return rolDe(u) === filtro;
  });

  const alCambiar = (m, quien) => { setMensaje(m); setResaltado(quien); cargar(); };
  const FILTROS = [['todos', 'Todos', conteos.total], ...Object.entries(ROLES).map(([v, t]) => [v, t, conteos[v]]),
    ['sin-rol', 'Sin rol', conteos.sinRol], ['inactivos', 'Inactivos', conteos.inactivos]];

  return (
    <section className="pagina pagina--ancha">
      <CabeceraPagina pestana="Usuarios y roles" eyebrow="Administración · HU-10" titulo={<>Usuarios y <em>roles</em></>} pintura="auto"
        descripcion="Un cambio de rol se aplica desde la siguiente petición del usuario: sus tokens anteriores dejan de servir y debe volver a entrar." />

      <div className="indicadores">
        {[['Usuarios', conteos.total, IconoUsuarios, 'neutro'], ['Activos', conteos.activos, CheckCircle2, 'musgo'],
          ['Inactivos', conteos.inactivos, UserX, 'terracota'], ['Sin rol', conteos.sinRol, UserMinus, 'ocre']].map(([t, v, Icono, tono]) => (
          <div key={t} className="indicador">
            <span className={`indicador__icono tono--${tono}`}><Icono className="icono" aria-hidden="true" /></span>
            <div><p className="indicador__valor">{pagina ? v : '—'}</p><p className="indicador__texto">{t}</p></div>
          </div>
        ))}
      </div>

      {mensaje && (
        <div className="aviso aviso--exito aviso--flotante" role="status">
          <CheckCircle2 className="icono" aria-hidden="true" /><div>{mensaje}</div>
        </div>
      )}

      <div className="usuarios">
        <div className="tarjeta">
          <form className="filtros-galeria" style={{ gridTemplateColumns: 'minmax(0, 1fr) auto' }}
            onSubmit={(e) => { e.preventDefault(); setConsulta({ busqueda: busqueda.trim(), page: 0 }); }}>
            <div className="campo">
              <label htmlFor="buscar-usuario">Buscar</label>
              <input id="buscar-usuario" value={busqueda} onChange={(e) => setBusqueda(e.target.value)} placeholder="Usuario, nombre o correo" />
            </div>
            <button type="submit" className="boton boton--fantasma"><Search className="icono" aria-hidden="true" />Buscar</button>
          </form>
          <div className="filtros-chips" role="group" aria-label="Filtrar por rol o estado">
            {FILTROS.map(([v, t, n]) => (
              <button key={v} type="button" aria-pressed={filtro === v} onClick={() => setFiltro(v)}
                className={`chip-filtro${TONOS_ROL[v] ? ` tono-borde--${TONOS_ROL[v]}` : ''}`}>
                {TONOS_ROL[v] && <span className={`punto tono--${TONOS_ROL[v]}`} aria-hidden="true" />}{t}<span className="chip-filtro__n">{pagina ? n : ''}</span>
              </button>
            ))}
          </div>
          <AvisoError error={error} />
          {!pagina && !error && <Cargando />}
          {pagina && visibles.length === 0 && <Vacio titulo="Nadie coincide">Prueba con otro filtro o búsqueda.</Vacio>}
          {visibles.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla usuarios__tabla">
                  <thead><tr><th>Persona</th><th>Rol</th><th>Estado</th><th><span className="solo-lectores">Acción</span></th></tr></thead>
                  <tbody>
                    {visibles.map((u) => <Fila key={u.id} u={u} esYo={u.usuario === usuario} resaltada={resaltado === u.usuario} alCambiar={alCambiar} />)}
                  </tbody>
                </table>
              </div>
              <Paginacion pagina={pagina.page} totalPaginas={pagina.totalPaginas} total={pagina.total} alCambiar={(page) => setConsulta((c) => ({ ...c, page }))} />
            </>
          )}
        </div>
        <Crear alCrear={(nuevo) => alCambiar(`${nuevo} creado. Entrégale la contraseña temporal.`, nuevo)} />
      </div>
    </section>
  );
}
