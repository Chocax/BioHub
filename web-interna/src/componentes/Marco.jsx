import { createContext, useContext, useMemo, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { BookOpen, Binoculars, ChartColumn, CirclePlus, House, KeyRound, LogOut, Map as IconoMapa, Menu, Search, Users } from 'lucide-react';
import { menuPara, nombreRol } from '../auth/permisos.js';
import { Monograma } from './Ilustraciones.jsx';
import { PINTURAS, pinturaAlAzar } from './pinturas.js';

/** Usuario y roles de la sesión: { usuario, roles, cerrarSesion }. */
export const SesionContexto = createContext({ usuario: '', roles: [], cerrarSesion: () => {} });
export const useSesion = () => useContext(SesionContexto);

/** Pintura de la sala abierta: la usan su cabecera y sus bordes, para que sean la misma obra. */
const PinturaSalaContexto = createContext(null);
export const usePinturaSala = () => useContext(PinturaSalaContexto);

const ICONOS_MENU = {
  '/': House, '/buscar': Search, '/hallazgos/nuevo': CirclePlus, '/mapa': IconoMapa, '/plantillas': BookOpen,
  '/equipos': Binoculars, '/reportes': ChartColumn, '/usuarios': Users, '/api-keys': KeyRound,
};

// Cada pantalla es una sala de la galería: su número y su nombre visten los bordes de la página.
const SALAS = [
  ['/buscar', '01', 'Hallazgos'], ['/hallazgos/nuevo', '02', 'Registro'], ['/mapa', '03', 'Mapa'],
  ['/plantillas', '04', 'Fichas'], ['/equipos', '05', 'Equipos'], ['/reportes', '06', 'Reportes'],
  ['/usuarios', '07', 'Usuarios'], ['/api-keys', '08', 'Llaves'],
];

function salaDe(ruta) {
  const exacta = SALAS.find(([r]) => r === ruta);
  if (exacta) return exacta;
  if (/^\/hallazgos\/[^/]+\/historial$/.test(ruta)) return [ruta, '01', 'Historial'];
  if (/^\/hallazgos\/[^/]+$/.test(ruta)) return [ruta, '01', 'Hallazgo'];
  return null;
}

/**
 * Bordes de la sala en pantallas anchas: a la izquierda el nombre en serif gigante, vertical y recortado contra el
 * borde (como la palabra BioHub del inicio); a la derecha una cartela de museo con el número de sala. Decorativo:
 * oculto para lectores de pantalla y sin eventos de puntero.
 */
function SalaLateral({ ruta, pintura }) {
  const sala = salaDe(ruta);
  if (!sala) return null;
  const [, numero, nombre] = sala;
  const obra = PINTURAS[pintura];
  return (
    <div className="sala-lateral" aria-hidden="true" style={{ '--pintura-sala': `url('${obra.chica}')` }}>
      <p className="sala-lateral__nombre">{nombre}</p>
      <div className="sala-lateral__columna">
        <div className="cartela muesca">
          <p className="eyebrow">Sala {numero}</p>
          <p className="cartela__nombre">{nombre}</p>
          <span className="cartela__regla" />
          <p className="cartela__obra">{obra.titulo}</p>
          <p className="cartela__autor">{obra.autor}</p>
        </div>
      </div>
    </div>
  );
}

/** Marco de todas las pantallas: barra mínima de galería, contenido y pie. */
export default function Marco() {
  const { usuario, roles, cerrarSesion } = useSesion();
  const [menuAbierto, setMenuAbierto] = useState(false);
  const ubicacion = useLocation();
  const entradas = menuPara(roles);
  // Una obra al azar por sala visitada: cambia al navegar, no en cada render.
  const pinturaSala = useMemo(() => pinturaAlAzar(), [ubicacion.pathname]);
  return (
    <div className="marco">
      <a href="#contenido" className="solo-lectores">Saltar al contenido</a>
      <header className="marco__barra">
        <NavLink to="/" className="marca" aria-label="BioHub, inicio">
          <Monograma />
          <span className="marca__nombre">BioHub</span>
        </NavLink>
        <button type="button" className="boton boton--fantasma boton--chico marco__menu" aria-expanded={menuAbierto}
          aria-controls="navegacion" onClick={() => setMenuAbierto((a) => !a)}><Menu className="icono" aria-hidden="true" />Menú</button>
        <nav id="navegacion" className="nav" aria-label="Principal" data-abierto={menuAbierto}
          onClick={() => setMenuAbierto(false)}>
          {entradas.map((e) => {
            const Icono = ICONOS_MENU[e.ruta];
            return <NavLink key={e.ruta} to={e.ruta} end={e.ruta === '/'}>{Icono && <Icono className="icono" aria-hidden="true" />}{e.texto}</NavLink>;
          })}
        </nav>
        <div className="usuario">
          <span><strong>{usuario}</strong> <span className="usuario__rol">· {nombreRol(roles)}</span></span>
          <button type="button" className="boton boton--chico" onClick={cerrarSesion}><LogOut className="icono" aria-hidden="true" />Salir</button>
        </div>
      </header>
      <main id="contenido" className="marco__cuerpo" key={ubicacion.pathname} data-ancho={ubicacion.pathname === '/usuarios'}>
        <PinturaSalaContexto.Provider value={pinturaSala}>
          <SalaLateral ruta={ubicacion.pathname} pintura={pinturaSala} />
          <Outlet />
        </PinturaSalaContexto.Provider>
      </main>
      <footer className="marco__pie">
        <span>BioHub · Sistema Nacional de Registro y Vigilancia de Biodiversidad</span>
        <span>Pinturas de dominio público: F. E. Church, M. J. Heade, Comisión Corográfica, E. W. Mark, Expedición Botánica y Humboldt</span>
      </footer>
    </div>
  );
}
