import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { buscarFichas } from '../api/fichas.js';
import Aviso from '../components/Aviso.jsx';
import AvisoUbicacionProtegida from '../components/AvisoUbicacionProtegida.jsx';
import Buscador from '../components/Buscador.jsx';
import ErrorDeConsulta from '../components/ErrorDeConsulta.jsx';
import EstadoVacio from '../components/EstadoVacio.jsx';
import Esqueleto from '../components/Esqueleto.jsx';
import Paginacion from '../components/Paginacion.jsx';
import TarjetaEspecie from '../components/TarjetaEspecie.jsx';
import { Follaje, Mariposa } from '../components/Ilustraciones.jsx';
import { textoTotal } from '../utilidades/formato.js';
import { useTitulo } from '../utilidades/useTitulo.js';

const TEXTO_DEGRADADO =
  'Hay muchas visitas en este momento. Por ahora solo puedes buscar por nombre; los filtros de familia y departamento están desactivados.';

/** Página de la URL (base 0, igual que la API). Valores raros caen a 0. */
function leerPagina(valor) {
  const n = Number.parseInt(valor ?? '', 10);
  return Number.isInteger(n) && n > 0 && n < 10000 ? n : 0;
}

export default function Inicio() {
  useTitulo('Buscar especies · BioHub');
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const familia = params.get('familia') ?? '';
  const departamento = params.get('departamento') ?? '';
  const page = leerPagina(params.get('page'));

  const [estado, setEstado] = useState({ tipo: 'cargando' });
  const [degradado, setDegradado] = useState(false);
  const [intento, setIntento] = useState(0);
  const refTitulo = useRef(null);
  const moverFoco = useRef(false);

  useEffect(() => {
    const control = new AbortController();
    setEstado({ tipo: 'cargando' });
    buscarFichas({ q, familia, departamento, page }, { signal: control.signal })
      .then((r) => {
        setDegradado(r.modoDegradado);
        setEstado({ tipo: 'ok', ...r });
      })
      .catch((e) => {
        if (e?.name === 'AbortError') return;
        setEstado({ tipo: 'error', error: e });
      });
    return () => control.abort();
  }, [q, familia, departamento, page, intento]);

  useEffect(() => {
    if (estado.tipo === 'ok' && moverFoco.current) {
      moverFoco.current = false;
      refTitulo.current?.focus();
    }
  }, [estado]);

  const aUrl = (v, pagina = 0) => {
    const p = new URLSearchParams();
    if (v.q) p.set('q', v.q);
    if (v.familia) p.set('familia', v.familia);
    if (v.departamento) p.set('departamento', v.departamento);
    if (pagina > 0) p.set('page', String(pagina));
    return p;
  };

  const buscar = (v) => {
    const nueva = aUrl(degradado ? { ...v, familia: '', departamento: '' } : v);
    if (nueva.toString() === params.toString()) setIntento((n) => n + 1); // misma búsqueda: se repite
    else setParams(nueva);
  };
  const cambiarPagina = (n) => {
    moverFoco.current = true;
    setParams(aUrl({ q, familia, departamento }, n));
  };
  const limpiar = () => setParams(new URLSearchParams());
  const hayFiltros = Boolean(familia || departamento);
  const volver = params.toString() ? `?${params}` : '';

  return (
    <>
      <section className="cielo" aria-labelledby="titulo-portal">
        <img className="cielo__pintura" src="/pinturas/nubes.webp" srcSet="/pinturas/nubes-800.webp 800w, /pinturas/nubes.webp 1200w"
          sizes="100vw" alt="" fetchPriority="high" />
        <div className="cielo__contenido">
          <p className="cielo__eyebrow">Sistema Nacional de Biodiversidad</p>
          <h1 id="titulo-portal" className="cielo__titulo">La biodiversidad de Colombia, <em>al alcance de todos</em></h1>
          <p className="cielo__bajada">Busca especies de flora y fauna registradas por las expediciones del Instituto. Las ubicaciones exactas de especies en peligro se mantienen protegidas.</p>
          <div className="cielo__buscador">
            <Buscador valores={{ q, familia, departamento }} degradado={degradado} onBuscar={buscar} />
          </div>
        </div>
        <Mariposa className="cielo__mariposa" />
      </section>

      <div className="franja-marca" aria-hidden="true">
        <p className="franja-marca__palabra">BioHub</p>
        <Follaje className="franja-marca__follaje franja-marca__follaje--izq" />
        <Follaje className="franja-marca__follaje franja-marca__follaje--der" invertir />
      </div>

      <div className="contenedor">
        {degradado ? (
          <Aviso tipo="advertencia">
            {TEXTO_DEGRADADO}
            {estado.tipo === 'ok' && estado.filtrosIgnorados.length > 0 ? ' Se ignoraron los filtros de familia y departamento.' : ''}
          </Aviso>
        ) : null}

        <section aria-busy={estado.tipo === 'cargando'} aria-label="Resultados">
          {estado.tipo === 'cargando' ? (
            <>
              <p role="status" className="solo-lectores">Buscando especies…</p>
              <div className="lista-tarjetas"><Esqueleto tipo="tarjeta" cantidad={6} /></div>
            </>
          ) : null}

          {estado.tipo === 'error' ? (
            <ErrorDeConsulta error={estado.error} onReintentar={() => setIntento((n) => n + 1)}>
              {estado.error?.status === 400 ? <button type="button" className="boton boton-secundario" onClick={limpiar}>Limpiar búsqueda</button> : null}
            </ErrorDeConsulta>
          ) : null}

          {estado.tipo === 'ok' && estado.total === 0 ? (
            <EstadoVacio
              titulo="No se encontraron especies"
              ayuda={`Revisa la ortografía o prueba con menos palabras.${hayFiltros ? ' Prueba quitando algún filtro.' : ''}`}
              textoAccion="Limpiar búsqueda"
              onAccion={limpiar}
            />
          ) : null}

          {estado.tipo === 'ok' && estado.total > 0 ? (
            <>
              <h2 className="resultados-titulo" tabIndex={-1} ref={refTitulo} aria-live="polite">{textoTotal(estado.total)}</h2>
              <ul className="lista-tarjetas" aria-label="Resultados de la búsqueda">
                {estado.fichas.map((f) => <TarjetaEspecie key={f.id} ficha={f} volver={volver} />)}
              </ul>
              <Paginacion pagina={estado.page} totalPaginas={estado.totalPaginas} onCambiar={cambiarPagina} />
            </>
          ) : null}
        </section>

        <AvisoUbicacionProtegida variante="breve" />
      </div>

      <section className="lienzo-publico" aria-labelledby="titulo-proteccion">
        <img className="lienzo-publico__pintura" src="/pinturas/tropico.webp" srcSet="/pinturas/tropico-800.webp 800w, /pinturas/tropico.webp 1800w"
          sizes="100vw" alt="" loading="lazy" />
        <div className="lienzo-publico__tarjeta">
          <p className="cielo__eyebrow">Por qué no ves coordenadas</p>
          <h2 id="titulo-proteccion">Proteger la ubicación también es proteger la especie</h2>
          <p>Si se supiera el lugar exacto de una especie en peligro, la caza y el tráfico ilegal podrían llegar hasta ella. Por eso el portal muestra solo el departamento y la región. Las personas investigadoras autorizadas consultan el mapa exacto dentro de la plataforma del Instituto.</p>
        </div>
      </section>

      <section className="claves contenedor" aria-labelledby="titulo-claves">
        <h2 id="titulo-claves" className="claves__titulo">Una sola fuente <em>confiable</em></h2>
        <ul className="claves__lista">
          <li>
            <span className="claves__circulo"><img src="/pinturas/colibri-800.webp" alt="" loading="lazy" style={{ objectPosition: '25% 80%' }} /></span>
            <h3>Datos de expedición</h3>
            <p>Cada ficha nace del trabajo de campo de investigadores del Instituto.</p>
          </li>
          <li>
            <span className="claves__circulo"><img src="/pinturas/andes-800.webp" alt="" loading="lazy" style={{ objectPosition: '20% 30%' }} /></span>
            <h3>Ciencia que se corrige</h3>
            <p>Si un análisis cambia la especie, la ficha se actualiza sin perder su historia.</p>
          </li>
          <li>
            <span className="claves__circulo"><img src="/pinturas/colibri-800.webp" alt="" loading="lazy" style={{ objectPosition: '62% 40%' }} /></span>
            <h3>Abierto a la ciencia</h3>
            <p>Universidades y ONG consultan estos datos por una API con límites justos.</p>
          </li>
        </ul>
      </section>
    </>
  );
}
