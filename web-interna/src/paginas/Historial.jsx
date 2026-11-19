import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { api } from '../api/cliente.js';
import { Hex } from '../componentes/Ilustraciones.jsx';
import { AvisoError, CabeceraPagina, Cargando, formatearFecha } from '../componentes/ui.jsx';

const texto = (v) => (v === null || v === undefined || v === '' ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v));

/** HU-05: historial inmutable, comparación de versiones y auditoría campo a campo. */
export default function Historial() {
  const { id } = useParams();
  const [versiones, setVersiones] = useState(null);
  const [auditoria, setAuditoria] = useState(null);
  const [error, setError] = useState(null);
  const [desde, setDesde] = useState(null);
  const [hasta, setHasta] = useState(null);
  const [comparacion, setComparacion] = useState(null);
  const [errorComparar, setErrorComparar] = useState(null);

  useEffect(() => {
    Promise.all([
      api.get(`/api/cientifico/hallazgos/${encodeURIComponent(id)}/versiones`),
      api.get(`/api/cientifico/hallazgos/${encodeURIComponent(id)}/auditoria`),
    ]).then(([v, a]) => {
      setVersiones(v);
      setAuditoria(a);
      if (v.length >= 2) { setHasta(v[0].version); setDesde(v[1].version); }
    }).catch(setError);
  }, [id]);

  useEffect(() => {
    if (desde == null || hasta == null || desde === hasta) { setComparacion(null); return; }
    setErrorComparar(null);
    api.get(`/api/cientifico/hallazgos/${encodeURIComponent(id)}/comparar?desde=${desde}&hasta=${hasta}`)
      .then(setComparacion).catch(setErrorComparar);
  }, [id, desde, hasta]);

  const especie = versiones?.[0]?.snapshot?.nombreCientifico;
  return (
    <section className="pagina">
      <CabeceraPagina pestana="Historial del hallazgo" eyebrow="Auditoría · HU-05" titulo={<>Historial <em>inmutable</em></>} pintura="auto"
        descripcion={especie ? `Todas las versiones de ${especie}. Nada se borra ni se sobrescribe: cada cambio es una versión nueva con su autor y su justificación.` : undefined}>
        <Link className="boton boton--fantasma" to={`/hallazgos/${id}`}>Volver al hallazgo</Link>
      </CabeceraPagina>
      <AvisoError error={error} />
      {!versiones && !error && <Cargando />}
      {versiones && (
        <div className="rejilla rejilla--2" style={{ alignItems: 'start' }}>
          <ol className="linea-tiempo" aria-label="Versiones del hallazgo">
            {versiones.map((v) => (
              <li key={v.id} className="linea-tiempo__item">
                <Hex className="hex linea-tiempo__marca" relleno={v.version === versiones[0].version} />
                <div className="tarjeta">
                  <p className="eyebrow">Versión {v.version} · {formatearFecha(v.creadoEn, true)}</p>
                  <p className="subtitulo"><i lang="la">{v.snapshot?.nombreCientifico}</i></p>
                  <p style={{ margin: '6px 0 0' }}>{v.justificacion ?? 'Registro inicial'}</p>
                  <p className="texto-suave" style={{ margin: '4px 0 0', fontSize: 13 }}>Por {v.autor}</p>
                </div>
              </li>
            ))}
          </ol>

          <div>
            <div className="tarjeta">
              <p className="tarjeta__titulo">Comparar versiones</p>
              {versiones.length < 2 ? (
                <p className="texto-suave">Hay una sola versión: todavía no se ha reclasificado.</p>
              ) : (
                <>
                  <div className="campos" style={{ margin: '12px 0 16px' }}>
                    {[['Desde', desde, setDesde], ['Hasta', hasta, setHasta]].map(([etiqueta, valor, cambiar]) => (
                      <div className="campo" key={etiqueta}>
                        <label htmlFor={`v-${etiqueta}`}>{etiqueta}</label>
                        <select id={`v-${etiqueta}`} value={valor ?? ''} onChange={(e) => cambiar(Number(e.target.value))}>
                          {versiones.map((v) => <option key={v.version} value={v.version}>Versión {v.version}</option>)}
                        </select>
                      </div>
                    ))}
                  </div>
                  <AvisoError error={errorComparar} />
                  {desde === hasta && <p className="texto-suave">Elige dos versiones distintas.</p>}
                  {comparacion && (
                    comparacion.diferencias.length === 0 ? <p className="texto-suave">Sin diferencias entre estas versiones.</p> : (
                      <div className="tabla-envoltura">
                        <table className="tabla">
                          <caption className="solo-lectores">Campos que cambiaron</caption>
                          <thead><tr><th>Campo</th><th>Versión {comparacion.versionAnterior}</th><th>Versión {comparacion.versionNueva}</th></tr></thead>
                          <tbody>
                            {comparacion.diferencias.map((d) => (
                              <tr key={d.campo}>
                                <th scope="row" style={{ textTransform: 'none', letterSpacing: 0 }}>{d.campo}</th>
                                <td><del className="diferencia diferencia--antes">{texto(d.valorAnterior)}</del></td>
                                <td><ins className="diferencia diferencia--despues">{texto(d.valorNuevo)}</ins></td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )
                  )}
                </>
              )}
            </div>

            <div className="tarjeta">
              <p className="tarjeta__titulo">Auditoría campo a campo</p>
              {auditoria?.length === 0 && <p className="texto-suave">Sin cambios registrados.</p>}
              {auditoria?.length > 0 && (
                <div className="tabla-envoltura">
                  <table className="tabla">
                    <thead><tr><th>Fecha</th><th>Campo</th><th>Antes → después</th><th>Autor</th></tr></thead>
                    <tbody>
                      {auditoria.map((c, i) => (
                        <tr key={i}>
                          <td>{formatearFecha(c.fecha, true)}</td>
                          <td>{c.campo}</td>
                          <td>{texto(c.valorPrevio)} → <strong>{texto(c.valorNuevo)}</strong><br /><span className="texto-suave" style={{ fontSize: 12 }}>{c.justificacion}</span></td>
                          <td>{c.autor}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
