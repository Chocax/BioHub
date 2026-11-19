import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/cliente.js';
import { Aviso, AvisoError, CabeceraPagina, Campo, Cargando, ChipEstado, formatearFecha, Paginacion, Vacio } from '../componentes/ui.jsx';

function NuevoConsumidor({ alCrear }) {
  const [datos, setDatos] = useState({ nombre: '', contactoEmail: '', limitePorMinuto: '' });
  const [error, setError] = useState(null);
  const cambiar = (c) => (e) => setDatos((d) => ({ ...d, [c]: e.target.value }));
  async function enviar(e) {
    e.preventDefault(); setError(null);
    try {
      const c = await api.post('/api/gateway/consumidores', { ...datos, limitePorMinuto: datos.limitePorMinuto ? Number(datos.limitePorMinuto) : null });
      setDatos({ nombre: '', contactoEmail: '', limitePorMinuto: '' });
      alCrear(c);
    } catch (falla) { setError(falla); }
  }
  return (
    <form className="tarjeta formulario" onSubmit={enviar}>
      <p className="tarjeta__titulo">Nuevo consumidor</p>
      <AvisoError error={error} titulo="No se creó" />
      <Campo etiqueta="Universidad u ONG" obligatorio>{(p) => <input {...p} required maxLength={150} value={datos.nombre} onChange={cambiar('nombre')} />}</Campo>
      <Campo etiqueta="Correo de contacto" obligatorio>{(p) => <input {...p} type="email" required value={datos.contactoEmail} onChange={cambiar('contactoEmail')} />}</Campo>
      <Campo etiqueta="Límite por minuto" ayuda="Vacío = 60 por minuto (RNF11)">{(p) => <input {...p} type="number" min="1" max="100000" value={datos.limitePorMinuto} onChange={cambiar('limitePorMinuto')} />}</Campo>
      <div><button type="submit" className="boton">Crear consumidor</button></div>
    </form>
  );
}

function Consumidor({ consumidor, alCambiar }) {
  const [keys, setKeys] = useState(null);
  const [limite, setLimite] = useState(consumidor.limitePorMinuto ?? '');
  const [expira, setExpira] = useState('');
  const [nueva, setNueva] = useState(null);
  const [error, setError] = useState(null);
  const [mensaje, setMensaje] = useState(null);
  const cargar = useCallback(() => api.get(`/api/gateway/consumidores/${consumidor.id}/keys`).then(setKeys).catch(setError), [consumidor.id]);
  useEffect(() => { cargar(); setNueva(null); setMensaje(null); setLimite(consumidor.limitePorMinuto ?? ''); }, [cargar, consumidor]);

  async function hacer(fn, texto) {
    setError(null); setMensaje(null);
    try { await fn(); if (texto) setMensaje(texto); } catch (falla) { setError(falla); }
  }

  return (
    <div className="tarjeta">
      <p className="eyebrow">Consumidor externo</p>
      <p className="subtitulo">{consumidor.nombre}</p>
      <p className="texto-suave" style={{ margin: '4px 0 16px' }}>{consumidor.contactoEmail} · desde {formatearFecha(consumidor.creadoEn)}</p>
      <AvisoError error={error} />
      {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
      {nueva && (
        <div className="tarjeta tarjeta--tinta muesca" role="status" style={{ marginBottom: 16 }}>
          <p className="eyebrow">Copia la key ahora: no se vuelve a mostrar</p>
          <p className="mono secreto">{nueva.apiKey}</p>
          <div className="acciones">
            <button type="button" className="boton boton--claro boton--chico" onClick={() => navigator.clipboard?.writeText(nueva.apiKey)}>Copiar</button>
            <button type="button" className="boton boton--fantasma boton--chico" style={{ color: '#fff', borderColor: '#fff' }} onClick={() => setNueva(null)}>Ya la guardé</button>
          </div>
          <p className="texto-suave" style={{ fontSize: 12, margin: '10px 0 0' }}>Se usa en la cabecera <span className="mono">X-API-Key</span> contra <span className="mono">/api/v1/externa/fichas</span>.</p>
        </div>
      )}
      <form className="acciones" style={{ alignItems: 'flex-end', marginBottom: 20 }}
        onSubmit={(e) => { e.preventDefault(); hacer(async () => { await api.put(`/api/gateway/consumidores/${consumidor.id}/limite`, { limitePorMinuto: limite === '' ? null : Number(limite) }); alCambiar(); }, 'Límite actualizado; aplica de inmediato.'); }}>
        <div className="campo" style={{ flex: '1 1 160px' }}>
          <label htmlFor="limite">Límite por minuto</label>
          <input id="limite" type="number" min="1" max="100000" value={limite} placeholder={`${consumidor.limiteEfectivo} (por defecto)`} onChange={(e) => setLimite(e.target.value)} />
        </div>
        <button type="submit" className="boton boton--fantasma">Guardar límite</button>
      </form>
      <form className="acciones" style={{ alignItems: 'flex-end', marginBottom: 16 }}
        onSubmit={(e) => { e.preventDefault(); hacer(async () => { setNueva(await api.post(`/api/gateway/consumidores/${consumidor.id}/keys`, expira ? { expiraEn: new Date(`${expira}T23:59:59-05:00`).toISOString() } : undefined)); setExpira(''); await cargar(); }); }}>
        <div className="campo" style={{ flex: '1 1 160px' }}>
          <label htmlFor="expira">Vence (opcional)</label>
          <input id="expira" type="date" value={expira} min={new Date().toISOString().slice(0, 10)} onChange={(e) => setExpira(e.target.value)} />
        </div>
        <button type="submit" className="boton">Emitir nueva key</button>
      </form>
      {!keys && <Cargando />}
      {keys?.length === 0 && <p className="texto-suave">Sin keys todavía.</p>}
      {keys?.length > 0 && (
        <div className="tabla-envoltura">
          <table className="tabla">
            <thead><tr><th>Prefijo</th><th>Estado</th><th>Último uso</th><th>Vence</th><th><span className="solo-lectores">Acción</span></th></tr></thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.id}>
                  <td className="mono">{k.prefijo}…</td>
                  <td><ChipEstado estado={k.estado} /></td>
                  <td>{k.ultimoUsoEn ? formatearFecha(k.ultimoUsoEn, true) : 'Nunca'}</td>
                  <td>{k.expiraEn ? formatearFecha(k.expiraEn) : '—'}</td>
                  <td>{k.estado === 'ACTIVA' && (
                    <button type="button" className="boton boton--peligro boton--chico"
                      onClick={() => { if (window.confirm(`¿Revocar ${k.prefijo}? Las peticiones con esa key se rechazan desde ya.`)) hacer(async () => { await api.borrar(`/api/gateway/keys/${k.id}`); await cargar(); }, 'Key revocada: se rechaza desde la siguiente petición.'); }}>Revocar</button>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** HU-08, HU-12 y RNF11: consumidores externos, sus API Keys y su límite por minuto. */
export default function ApiKeys() {
  const [page, setPage] = useState(0);
  const [pagina, setPagina] = useState(null);
  const [error, setError] = useState(null);
  const [seleccion, setSeleccion] = useState(null);
  const cargar = useCallback(() => api.get(`/api/gateway/consumidores?page=${page}&size=10`).then((p) => {
    setPagina(p);
    setSeleccion((s) => (s ? p.content.find((c) => c.id === s.id) ?? s : p.content[0] ?? null));
  }).catch(setError), [page]);
  useEffect(() => { cargar(); }, [cargar]);

  return (
    <section className="pagina">
      <CabeceraPagina pestana="API Keys" eyebrow="API externa · HU-12" titulo={<>Llaves para <em>universidades</em> y ONG</>}
        descripcion="Cada consumidor recibe sus API Keys y un límite de peticiones por minuto. Una key revocada se rechaza desde la siguiente petición." />
      <AvisoError error={error} />
      <div className="rejilla" style={{ gridTemplateColumns: 'minmax(260px, 1fr) minmax(0, 2fr)', alignItems: 'start' }}>
        <div>
          <div className="tarjeta">
            <p className="tarjeta__titulo">Consumidores</p>
            {!pagina && <Cargando />}
            {pagina?.content.length === 0 && <Vacio titulo="Sin consumidores" />}
            <ul className="lista-seleccion">
              {pagina?.content.map((c) => (
                <li key={c.id}>
                  <button type="button" aria-pressed={seleccion?.id === c.id} onClick={() => setSeleccion(c)}>
                    <span><strong>{c.nombre}</strong><span className="texto-suave" style={{ display: 'block', fontSize: 12 }}>{c.limiteEfectivo} por minuto</span></span>
                  </button>
                </li>
              ))}
            </ul>
            {pagina && <Paginacion pagina={pagina.page} totalPaginas={pagina.totalPaginas} total={pagina.total} alCambiar={setPage} />}
          </div>
          <NuevoConsumidor alCrear={(c) => { setSeleccion(c); cargar(); }} />
        </div>
        {seleccion ? <Consumidor consumidor={seleccion} alCambiar={cargar} /> : <div className="tarjeta"><Vacio titulo="Elige un consumidor" /></div>}
      </div>
    </section>
  );
}
