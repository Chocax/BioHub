import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api/cliente.js';
import { nombreRol, puede } from '../auth/permisos.js';
import { useSesion } from '../componentes/Marco.jsx';
import { Follaje, Hex, Mariposa } from '../componentes/Ilustraciones.jsx';
import { useRevelar, useTitulo } from '../componentes/ui.jsx';

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

      <section className="lienzo" aria-label="Sobre BioHub">
        <img className="lienzo__imagen" src="/pinturas/andes.webp" srcSet="/pinturas/andes-800.webp 800w, /pinturas/andes.webp 2000w"
          sizes="100vw" alt="" loading="lazy" />
        <div className="lienzo__tarjeta muesca">
          <h2>La ciencia se corrige, el historial se conserva</h2>
          <p>
            Cada hallazgo guarda todas sus versiones, quién las autorizó y por qué. Las coordenadas de especies en
            peligro viajan cifradas y solo el personal de alto rango ve el mapa exacto.
          </p>
          <div className="lienzo__pie">
            <span>Desplázate</span>
            <span className="hexes" aria-hidden="true"><Hex relleno /><Hex /><Hex /></span>
          </div>
        </div>
      </section>

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
