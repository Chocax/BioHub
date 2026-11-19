import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { api } from '../api/cliente.js';
import { nombreRol, puede } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import { Follaje, Hex, Mariposa } from '../componentes/Ilustraciones.jsx';
import { useRevelar, useTitulo } from '../componentes/ui.jsx';
import { PINTURAS } from '../componentes/pinturas.js';

/** Módulos de la sala, con la pintura y el encuadre de cada viñeta. */
const MODULOS = [
  { capacidad: 'registrarHallazgo', ruta: '/hallazgos/nuevo', titulo: 'Registrar hallazgo', texto: 'Carga un espécimen con su ficha, fotos y audios.', imagen: 'colibri', encuadre: '62% 40%' },
  { capacidad: 'buscarHallazgos', ruta: '/buscar', titulo: 'Explorar hallazgos', texto: 'Filtra por especie, fecha, investigador o departamento.', imagen: 'rio', encuadre: '40% 60%' },
  { capacidad: 'verMapa', ruta: '/mapa', titulo: 'Mapa de coordenadas', texto: 'Ubicaciones exactas, solo para alto rango.', imagen: 'andes', encuadre: '18% 30%' },
  { capacidad: 'verPlantillas', ruta: '/plantillas', titulo: 'Fichas taxonómicas', texto: 'Atributos flexibles por especie.', imagen: 'andes', encuadre: '88% 70%' },
  { capacidad: 'equipos', ruta: '/equipos', titulo: 'Equipos de campo', texto: 'Drones, cámaras trampa y sensores con su custodio.', imagen: 'andes', encuadre: '62% 85%' },
  { capacidad: 'reportes', ruta: '/reportes', titulo: 'Presión sobre ecosistemas', texto: 'Reporte mensual con zonas de alerta.', imagen: 'rio', encuadre: '85% 30%' },
  { capacidad: 'usuarios', ruta: '/usuarios', titulo: 'Usuarios y roles', texto: 'Altas, cambios de rol y accesos.', imagen: 'andes', encuadre: '50% 50%' },
  { capacidad: 'apiKeys', ruta: '/api-keys', titulo: 'API para externos', texto: 'Llaves y límites para universidades y ONG.', imagen: 'rio', encuadre: '20% 20%' },
];

const ACCION_PRINCIPAL = [
  ['registrarHallazgo', '/hallazgos/nuevo', 'Registrar hallazgo'],
  ['reportes', '/reportes', 'Ver reportes'],
  ['verHistorial', '/buscar', 'Auditar hallazgos'],
  ['equipos', '/equipos', 'Gestionar equipos'],
  ['buscarHallazgos', '/buscar', 'Explorar hallazgos'],
];

/** Tres pinturas que se alternan despacio, cada una con su mensaje (la tarjeta negra flotante de la galería). */
const DIAPOSITIVAS = [
  { pintura: 'andes', titulo: 'La ciencia se corrige, el historial se conserva',
    texto: 'Cada hallazgo guarda todas sus versiones, quién las autorizó y por qué. Nada se borra: una reclasificación crea una versión nueva.' },
  { pintura: 'rio', titulo: 'Las coordenadas viajan cifradas',
    texto: 'La ubicación exacta de una especie en peligro se guarda cifrada. El portal público solo muestra el departamento y el mapa exacto es del personal de alto rango.' },
  { pintura: 'colibri', titulo: 'Cada especie, su propia ficha',
    texto: 'Los investigadores definen qué se mide de cada especie, como el canto de un ave o la temperatura del agua de una rana, sin tocar código.' },
  { pintura: 'tequendama', titulo: 'Un país que cabe en un inventario',
    texto: 'Colombia es uno de los países más biodiversos del planeta. Cada hallazgo que registras suma al inventario que ayuda a protegerla.' },
  { pintura: 'lluvia', titulo: 'Dos siglos de expedicionarios',
    texto: 'De la Expedición Botánica de Mutis a la Comisión Corográfica, el país lleva más de doscientos años documentando su naturaleza. BioHub continúa ese trabajo.' },
];
const INTERVALO_MS = 18000;

function LienzoRotativo() {
  const [actual, setActual] = useState(0);
  const [cambiando, setCambiando] = useState(false);
  const [pausa, setPausa] = useState(false);
  const temporizador = useRef(null);

  const ir = (indice) => {
    if (indice === actual) return;
    setCambiando(true);
    window.clearTimeout(temporizador.current);
    temporizador.current = window.setTimeout(() => { setActual(indice); setCambiando(false); }, 450);
  };

  useEffect(() => {
    const reducir = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (pausa || reducir) return undefined;
    const t = window.setInterval(() => ir((actual + 1) % DIAPOSITIVAS.length), INTERVALO_MS);
    return () => window.clearInterval(t);
  });
  useEffect(() => () => window.clearTimeout(temporizador.current), []);

  const total = DIAPOSITIVAS.length;
  const anterior = () => ir((actual - 1 + total) % total);
  const siguiente = () => ir((actual + 1) % total);
  const alTeclear = (e) => {
    if (e.key === 'ArrowLeft') { e.preventDefault(); anterior(); }
    if (e.key === 'ArrowRight') { e.preventDefault(); siguiente(); }
  };

  const diapositiva = DIAPOSITIVAS[actual];
  const cuadro = PINTURAS[diapositiva.pintura];
  return (
    <section className="lienzo" aria-label="Sobre BioHub" aria-roledescription="carrusel"
      onMouseEnter={() => setPausa(true)} onMouseLeave={() => setPausa(false)}
      onFocus={() => setPausa(true)} onBlur={() => setPausa(false)} onKeyDown={alTeclear}>
      {DIAPOSITIVAS.map((d, i) => (
        <img key={d.pintura} className="lienzo__imagen" data-activa={i === actual}
          src={PINTURAS[d.pintura].src} srcSet={`${PINTURAS[d.pintura].chica} 800w, ${PINTURAS[d.pintura].src} 2000w`}
          sizes="100vw" alt="" loading="lazy" />
      ))}
      <button type="button" className="lienzo__flecha lienzo__flecha--izq" onClick={anterior} aria-label="Pintura anterior">
        <ChevronLeft aria-hidden="true" />
      </button>
      <button type="button" className="lienzo__flecha lienzo__flecha--der" onClick={siguiente} aria-label="Pintura siguiente">
        <ChevronRight aria-hidden="true" />
      </button>
      <div className="lienzo__tarjeta muesca">
        <div className="lienzo__texto" data-cambiando={cambiando} aria-live="polite">
          <h2>{diapositiva.titulo}</h2>
          <p>{diapositiva.texto}</p>
        </div>
        <div className="lienzo__pie">
          <span>{String(actual + 1).padStart(2, '0')} / {String(DIAPOSITIVAS.length).padStart(2, '0')}</span>
          <span className="lienzo__puntos">
            {DIAPOSITIVAS.map((d, i) => (
              <button key={d.pintura} type="button" className="lienzo__punto" aria-current={i === actual}
                aria-label={`Ver: ${d.titulo}`} onClick={() => ir(i)}>
                <Hex relleno={i === actual} />
              </button>
            ))}
          </span>
        </div>
      </div>
      <p className="lienzo__credito">{cuadro.titulo} · {cuadro.autor}</p>
    </section>
  );
}

export default function Inicio() {
  const { usuario, roles } = useSesion();
  const titulo = useTitulo('Inicio');
  const sala = useRevelar();
  const [total, setTotal] = useState(null);

  useEffect(() => {
    if (!puede(roles, 'buscarHallazgos')) return undefined;
    const control = new AbortController();
    api.get('/api/cientifico/ubicacion/hallazgos?page=0&size=1', { senal: control.signal })
      .then((r) => setTotal(r?.total ?? r?.totalElements ?? null))
      .catch(() => setTotal(null));
    return () => control.abort();
  }, [roles]);

  const accion = ACCION_PRINCIPAL.find(([capacidad]) => puede(roles, capacidad));
  const modulos = MODULOS.filter((m) => puede(roles, m.capacidad));

  return (
    <>
      <section className="heroe" aria-labelledby="inicio-titulo">
        <div className="heroe__grupo">
          <p className="eyebrow">Hola, {usuario} · {nombreRol(roles)}</p>
          <h1 id="inicio-titulo" className="heroe__titulo" tabIndex={-1} ref={titulo}>
            Inventario <em>vivo de la</em><br />biodiversidad
          </h1>
          <div className="heroe__datos">
            {total != null && <span className="dato">Hallazgos: {total.toLocaleString('es-CO')}</span>}
            <span className="dato">Colombia · 33 departamentos</span>
          </div>
          {accion && <Link className="boton" to={accion[1]}>{accion[2]}</Link>}
        </div>
        <Mariposa className="heroe__mariposa" />
        <p className="heroe__palabra" aria-hidden="true">BioHub</p>
        <Follaje className="heroe__follaje heroe__follaje--izq" />
        <Follaje className="heroe__follaje heroe__follaje--der" invertir />
      </section>

      <LienzoRotativo />

      {modulos.length > 0 ? (
        <section className="sala" aria-labelledby="sala-titulo" ref={sala}>
          <h2 id="sala-titulo" className="sala__titulo revelar">Tu <em>galería</em></h2>
          <p className="sala__bajada revelar">Lo que tu rol puede hacer en BioHub</p>
          <div className="vinetas">
            {modulos.map((m, i) => (
              <Link key={m.ruta} to={m.ruta} className="vineta revelar" style={{ transitionDelay: `${i * 70}ms` }}>
                <p className="vineta__titulo">{m.titulo}</p>
                <span className="vineta__circulo">
                  <img src={`/pinturas/${m.imagen}-800.webp`} alt="" loading="lazy" style={{ objectPosition: m.encuadre }} />
                </span>
                <p className="vineta__texto">{m.texto}</p>
                <span className="hexes" aria-hidden="true"><Hex /><Hex relleno={i % 3 === 0} /><Hex /></span>
              </Link>
            ))}
          </div>
        </section>
      ) : (
        <section className="sala">
          <h2 className="sala__titulo">Sin <em>rol</em> asignado</h2>
          <p className="sala__bajada">Pide a un administrador que te asigne un rol para entrar a los módulos.</p>
        </section>
      )}
    </>
  );
}
