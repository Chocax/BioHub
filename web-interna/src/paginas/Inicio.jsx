import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { api } from '../api/cliente.js';
import { nombreRol, puede } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import { Follaje, Hex, Mariposa } from '../componentes/Ilustraciones.jsx';
import { useRevelar, useTitulo } from '../componentes/ui.jsx';
import { ordenAleatorio } from '../componentes/ordenAleatorio.js';
import { FONDOS_GRANDES, PINTURAS } from '../componentes/pinturas.js';

/** Módulos de la sala. Cada viñeta turna tres obras (la primera con su encuadre propio). */
const MODULOS = [
  { capacidad: 'registrarHallazgo', ruta: '/hallazgos/nuevo', titulo: 'Registrar hallazgo', texto: 'Carga un espécimen con su ficha, fotos y audios.', obras: ['colibri', 'pasiflora', 'tropico'], encuadre: '62% 40%' },
  { capacidad: 'buscarHallazgos', ruta: '/buscar', titulo: 'Explorar hallazgos', texto: 'Filtra por especie, fecha, investigador o departamento.', obras: ['rio', 'lluvia', 'andes'], encuadre: '40% 60%' },
  { capacidad: 'verMapa', ruta: '/mapa', titulo: 'Mapa de coordenadas', texto: 'Ubicaciones exactas, solo para alto rango.', obras: ['chimborazo', 'chita', 'icononzo'], encuadre: '50% 35%' },
  { capacidad: 'verPlantillas', ruta: '/plantillas', titulo: 'Fichas taxonómicas', texto: 'Atributos flexibles por especie.', obras: ['pitahaya', 'condor', 'mutisia'], encuadre: '35% 68%' },
  { capacidad: 'equipos', ruta: '/equipos', titulo: 'Equipos de campo', texto: 'Drones, cámaras trampa y sensores con su custodio.', obras: ['sangay', 'bongo', 'cachiri'], encuadre: '58% 40%' },
  { capacidad: 'reportes', ruta: '/reportes', titulo: 'Presión sobre ecosistemas', texto: 'Reporte mensual con zonas de alerta.', obras: ['mariquita', 'nubes', 'choachi'], encuadre: '45% 60%' },
  { capacidad: 'usuarios', ruta: '/usuarios', titulo: 'Usuarios y roles', texto: 'Altas, cambios de rol y accesos.', obras: ['sudamerica', 'santa-marta', 'guacamaya'], encuadre: '45% 55%' },
  { capacidad: 'apiKeys', ruta: '/api-keys', titulo: 'API para externos', texto: 'Llaves y límites para universidades y ONG.', obras: ['tequendama-cima', 'tequendama', 'andes'], encuadre: '42% 50%' },
];
const VINETA_MS = 7000;

const ACCION_PRINCIPAL = [
  ['registrarHallazgo', '/hallazgos/nuevo', 'Registrar hallazgo'],
  ['reportes', '/reportes', 'Ver reportes'],
  ['verHistorial', '/buscar', 'Auditar hallazgos'],
  ['equipos', '/equipos', 'Gestionar equipos'],
  ['buscarHallazgos', '/buscar', 'Explorar hallazgos'],
];

/**
 * Mensajes de la tarjeta negra del carrusel. Las pinturas son las 20 de FONDOS_GRANDES en orden aleatorio, y estos
 * mensajes se turnan encima de ellas (no dependen de una obra en particular).
 */
const MENSAJES = [
  { titulo: 'La ciencia se corrige, el historial se conserva',
    texto: 'Cada hallazgo guarda todas sus versiones, quién las autorizó y por qué. Nada se borra: una reclasificación crea una versión nueva.' },
  { titulo: 'Las coordenadas viajan cifradas',
    texto: 'La ubicación exacta de una especie en peligro se guarda cifrada. El portal público solo muestra el departamento y el mapa exacto es del personal de alto rango.' },
  { titulo: 'Cada especie, su propia ficha',
    texto: 'Los investigadores definen qué se mide de cada especie, como el canto de un ave o la temperatura del agua de una rana, sin tocar código.' },
  { titulo: 'Un país que cabe en un inventario',
    texto: 'Colombia es uno de los países más biodiversos del planeta. Cada hallazgo que registras suma al inventario que ayuda a protegerla.' },
  { titulo: 'Dos siglos de expedicionarios',
    texto: 'De la Expedición Botánica de Mutis a la Comisión Corográfica, el país lleva más de doscientos años documentando su naturaleza. BioHub continúa ese trabajo.' },
  { titulo: 'Del páramo al bosque de niebla',
    texto: 'En pocos kilómetros de cordillera cambian el clima, las plantas y los animales. Por eso cada hallazgo guarda su departamento y, cifrado, su punto exacto.' },
  { titulo: 'Dibujado en el camino',
    texto: 'Los viajeros del siglo XIX dibujaban lo que veían, con notas al margen. Hoy la misma curiosidad se registra con fotos, audios y una ficha por especie.' },
];
const INTERVALO_MS = 18000;

/**
 * Viñeta de un módulo: el título, el círculo y el texto llevan a la sala; las tres obras se turnan solas con fundido
 * (cada viñeta con un desfase, para que no cambien todas a la vez) y los hexágonos de abajo eligen la obra a mano.
 * Se detiene al pasar el mouse o con el foco, y no se mueve con «reducir movimiento».
 */
function Vineta({ modulo, orden }) {
  const [actual, setActual] = useState(0);
  const [pausa, setPausa] = useState(false);
  const [vuelta, setVuelta] = useState(0); // reinicia el turno al elegir una obra a mano
  const obras = modulo.obras.map((clave, i) => ({ clave, ...PINTURAS[clave], encuadre: i === 0 ? modulo.encuadre : PINTURAS[clave]?.encuadre }));

  useEffect(() => {
    if (pausa || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return undefined;
    let intervalo;
    const inicio = setTimeout(() => {
      setActual((a) => (a + 1) % obras.length);
      intervalo = setInterval(() => setActual((a) => (a + 1) % obras.length), VINETA_MS);
    }, VINETA_MS + orden * 900);
    return () => { clearTimeout(inicio); clearInterval(intervalo); };
  }, [pausa, vuelta, orden, obras.length]);

  return (
    <div className="vineta revelar" style={{ transitionDelay: `${orden * 70}ms` }}
      onMouseEnter={() => setPausa(true)} onMouseLeave={() => setPausa(false)}
      onFocus={() => setPausa(true)} onBlur={() => setPausa(false)}>
      <Link to={modulo.ruta} className="vineta__enlace">
        <p className="vineta__titulo">{modulo.titulo}</p>
        <span className="vineta__circulo">
          {obras.map((o, i) => (
            <img key={o.clave} src={o.chica} alt="" loading="lazy" style={{ objectPosition: o.encuadre }}
              className={i === actual ? 'vineta__obra vineta__obra--activa' : 'vineta__obra'} />
          ))}
        </span>
        <p className="vineta__texto">{modulo.texto}</p>
      </Link>
      <div className="vineta__obras" role="group" aria-label={`Obras de ${modulo.titulo}`}>
        {obras.map((o, i) => (
          <button key={o.clave} type="button" className="vineta__punto" aria-pressed={i === actual}
            aria-label={`${o.titulo}, ${o.autor}`} title={`${o.titulo} · ${o.autor}`}
            onClick={() => { setActual(i); setVuelta((v) => v + 1); }}>
            <Hex relleno={i === actual} />
          </button>
        ))}
      </div>
    </div>
  );
}

function LienzoRotativo() {
  // Orden al azar en cada visita, y nunca la misma diapositiva de arranque que la vez anterior.
  const [diapositivas] = useState(() => ordenAleatorio(FONDOS_GRANDES, 'biohub.lienzo.primera', (p) => p)
    .map((pintura, i) => ({ pintura, ...MENSAJES[i % MENSAJES.length] })));
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
    const t = window.setInterval(() => ir((actual + 1) % diapositivas.length), INTERVALO_MS);
    return () => window.clearInterval(t);
  });
  useEffect(() => () => window.clearTimeout(temporizador.current), []);

  const total = diapositivas.length;
  const anterior = () => ir((actual - 1 + total) % total);
  const siguiente = () => ir((actual + 1) % total);
  const alTeclear = (e) => {
    if (e.key === 'ArrowLeft') { e.preventDefault(); anterior(); }
    if (e.key === 'ArrowRight') { e.preventDefault(); siguiente(); }
  };

  const diapositiva = diapositivas[actual];
  const cuadro = PINTURAS[diapositiva.pintura];
  return (
    <section className="lienzo" aria-label="Sobre BioHub" aria-roledescription="carrusel"
      onMouseEnter={() => setPausa(true)} onMouseLeave={() => setPausa(false)}
      onFocus={() => setPausa(true)} onBlur={() => setPausa(false)} onKeyDown={alTeclear}>
      {/* Solo la actual y sus vecinas: con 20 obras, montarlas todas bajaría varios MB al abrir el inicio */}
      {diapositivas.map((d, i) => ({ d, i })).filter(({ i }) => [actual, (actual + 1) % total, (actual - 1 + total) % total].includes(i)).map(({ d, i }) => (
        <img key={d.pintura} className="lienzo__imagen" data-activa={i === actual}
          src={PINTURAS[d.pintura].src} srcSet={`${PINTURAS[d.pintura].chica} 800w, ${PINTURAS[d.pintura].src} 2000w`}
          sizes="100vw" alt="" loading="lazy" style={{ objectPosition: PINTURAS[d.pintura].encuadre }} />
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
          <span>{String(actual + 1).padStart(2, '0')} / {String(diapositivas.length).padStart(2, '0')}</span>
          {/* Con 20 obras no caben puntos: una línea de avance (para moverse están las flechas y las teclas ← →) */}
          <span className="lienzo__progreso" aria-hidden="true">
            <span style={{ width: `${((actual + 1) / total) * 100}%` }} />
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
            {modulos.map((m, i) => <Vineta key={m.ruta} modulo={m} orden={i} />)}
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
