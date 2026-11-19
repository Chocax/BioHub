import { useCallback, useEffect, useState } from 'react';
import { api, descargar } from '../api/cliente.js';
import { Aviso, AvisoError, CabeceraPagina, Cargando, ChipEstado, formatearFecha, Vacio } from '../componentes/ui.jsx';

const mesAnterior = () => {
  const d = new Date(new Date().toLocaleString('en-US', { timeZone: 'America/Bogota' }));
  d.setDate(1); d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
};
const enCurso = (s) => s.estado === 'PENDIENTE' || s.estado === 'EN_PROCESO';
const nombreMes = (periodo) => {
  const [a, m] = periodo.split('-').map(Number);
  const texto = new Date(a, m - 1, 15).toLocaleDateString('es-CO', { month: 'long', year: 'numeric' });
  return texto.charAt(0).toUpperCase() + texto.slice(1);
};

function Resultado({ solicitud }) {
  const r = solicitud.resumen;
  if (solicitud.estado === 'FALLIDO') return <Aviso tipo="error">{solicitud.error ?? 'El reporte no se pudo generar.'}</Aviso>;
  if (!r) return <Cargando texto="Generando el reporte en segundo plano…" />;
  return (
    <>
      <div className="cifras">
        <div><p className="eyebrow">Hallazgos</p><p className="cifra">{r.totalHallazgos}</p></div>
        <div><p className="eyebrow">En peligro</p><p className="cifra">{r.totalEnPeligro}</p></div>
        <div><p className="eyebrow">Departamentos</p><p className="cifra">{r.departamentos}</p></div>
      </div>
      {r.zonasEnAlerta.length > 0
        ? <Aviso tipo="error"><strong>Zonas en alerta:</strong> {r.zonasEnAlerta.join(', ')}. Presión alta y en aumento frente a los tres meses anteriores.</Aviso>
        : <Aviso tipo="exito">Ningún departamento en alerta este mes.</Aviso>}
      {r.indices.length === 0 ? <Vacio titulo="Sin hallazgos en el periodo" /> : (
        <div className="tabla-envoltura">
          <table className="tabla">
            <caption className="solo-lectores">Índice de presión por departamento</caption>
            <thead><tr><th>Departamento</th><th>Índice</th><th>Nivel</th><th>En peligro / total</th><th>Tendencia</th></tr></thead>
            <tbody>
              {r.indices.map((i) => (
                <tr key={i.departamento}>
                  <td><strong>{i.departamento}</strong>{i.alerta && <span className="chip chip--terracota" style={{ marginLeft: 8 }}>Alerta</span>}</td>
                  <td style={{ minWidth: 170 }}>
                    <div className="barra" role="img" aria-label={`Índice ${i.indice} de 100`}>
                      <span className={`barra__relleno barra__relleno--${i.nivel.toLowerCase()}`} style={{ width: `${Math.min(100, i.indice)}%` }} />
                    </div>
                    <span className="mono">{i.indice.toFixed(1)}</span>
                  </td>
                  <td><ChipEstado estado={i.nivel} /></td>
                  <td>{i.enPeligro} / {i.hallazgos}</td>
                  <td>{i.enPeligro > i.promedioEnPeligroPrevio ? '↑' : i.enPeligro < i.promedioEnPeligroPrevio ? '↓' : '='} prom. {i.promedioEnPeligroPrevio}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="texto-suave" style={{ fontSize: 12.5 }}>
        Índice = 100 × (0,7 × proporción de hallazgos en peligro del mes + 0,3 × crecimiento frente al promedio de los 3 meses anteriores). Alto ≥ 50, medio ≥ 25.
      </p>
      <div className="acciones">
        <button type="button" className="boton" onClick={() => descargar(`/api/reportes/solicitudes/${solicitud.id}/pdf`, `presion-ecosistemas-${solicitud.periodo}.pdf`)}>Descargar PDF</button>
        <button type="button" className="boton boton--fantasma" onClick={() => descargar(`/api/reportes/solicitudes/${solicitud.id}/csv`, `presion-ecosistemas-${solicitud.periodo}.csv`)}>Descargar CSV</button>
      </div>
    </>
  );
}

/** HU-09, RF15 y RF16: reporte mensual de presión sobre los ecosistemas, generado en segundo plano. */
export default function Reportes() {
  const [periodo, setPeriodo] = useState(mesAnterior());
  const [solicitudes, setSolicitudes] = useState(null);
  const [seleccion, setSeleccion] = useState(null);
  const [error, setError] = useState(null);
  const [pidiendo, setPidiendo] = useState(false);

  const cargar = useCallback(() => api.get('/api/reportes/solicitudes').then((l) => {
    setSolicitudes(l);
    setSeleccion((s) => (s ? l.find((x) => x.id === s.id) ?? s : l.find((x) => x.estado === 'LISTO') ?? l[0] ?? null));
  }).catch(setError), []);
  useEffect(() => { cargar(); }, [cargar]);

  // Mientras haya alguno en curso, se consulta el estado cada 2 s (HU-09 c.1: el navegador nunca se bloquea).
  useEffect(() => {
    if (!solicitudes?.some(enCurso)) return undefined;
    const t = setInterval(cargar, 2000);
    return () => clearInterval(t);
  }, [solicitudes, cargar]);

  async function pedir(e) {
    e.preventDefault();
    setPidiendo(true); setError(null);
    try {
      const nueva = await api.post('/api/reportes/solicitudes', { periodo });
      setSeleccion(nueva);
      await cargar();
    } catch (falla) { setError(falla); } finally { setPidiendo(false); }
  }

  return (
    <section className="pagina">
      <CabeceraPagina pestana="Reportes" eyebrow="Analítica · HU-09" titulo={<>Presión sobre los <em>ecosistemas</em></>}
        descripcion="El reporte se calcula en segundo plano sobre la base analítica, sin tocar el registro diario. Puedes seguir trabajando mientras se genera. Cada mes se pide uno automático del mes anterior." />
      <AvisoError error={error} />
      <div className="rejilla" style={{ gridTemplateColumns: 'minmax(260px, 1fr) minmax(0, 2.4fr)', alignItems: 'start' }}>
        <div>
          <form className="tarjeta tarjeta--tinta muesca formulario" onSubmit={pedir}>
            <p className="eyebrow">Nuevo reporte</p>
            <div className="campo">
              <label htmlFor="periodo" style={{ color: '#d6d4cd' }}>Mes</label>
              <input id="periodo" type="month" value={periodo} max={new Date().toISOString().slice(0, 7)} onChange={(e) => setPeriodo(e.target.value)} required />
            </div>
            <button type="submit" className="boton boton--claro" disabled={pidiendo || !periodo}>{pidiendo ? 'Pidiendo…' : 'Generar reporte'}</button>
          </form>
          <div className="tarjeta">
            <p className="tarjeta__titulo">Recientes</p>
            {!solicitudes && <Cargando />}
            {solicitudes?.length === 0 && <p className="texto-suave">Todavía no hay reportes.</p>}
            <ul className="lista-seleccion">
              {solicitudes?.map((s) => (
                <li key={s.id}>
                  <button type="button" aria-pressed={seleccion?.id === s.id} onClick={() => setSeleccion(s)}>
                    <span><strong>{nombreMes(s.periodo)}</strong>
                      <span className="texto-suave" style={{ display: 'block', fontSize: 12 }}>{s.origen === 'PROGRAMADO' ? 'Automático' : s.solicitadoPor} · {formatearFecha(s.solicitadoEn, true)}</span></span>
                    <ChipEstado estado={s.estado} />
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
        <div className="tarjeta" aria-live="polite">
          {seleccion ? (
            <>
              <p className="eyebrow">Reporte · {seleccion.origen === 'PROGRAMADO' ? 'automático' : 'manual'}</p>
              <p className="subtitulo" style={{ marginBottom: 16 }}>{nombreMes(seleccion.periodo)}</p>
              <Resultado solicitud={seleccion} />
            </>
          ) : <Vacio titulo="Elige o genera un reporte" />}
        </div>
      </div>
    </section>
  );
}
