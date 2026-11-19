import { createContext, useContext, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { menuPara, nombreRol } from '../auth/permisos.js';
import { Monograma } from './Ilustraciones.jsx';

/** Usuario y roles de la sesión: { usuario, roles, cerrarSesion }. */
export const SesionContexto = createContext({ usuario: '', roles: [], cerrarSesion: () => {} });
export const useSesion = () => useContext(SesionContexto);

/** Marco de todas las pantallas: barra mínima de galería, contenido y pie. */
export default function Marco() {
  const { usuario, roles, cerrarSesion } = useSesion();
  const [menuAbierto, setMenuAbierto] = useState(false);
  const ubicacion = useLocation();
  const entradas = menuPara(roles);
  return (
    <div className="marco">
      <a href="#contenido" className="solo-lectores">Saltar al contenido</a>
      <header className="marco__barra">
        <NavLink to="/" className="marca" aria-label="BioHub, inicio">
          <Monograma />
          <span className="marca__nombre">BioHub</span>
        </NavLink>
        <button type="button" className="boton boton--fantasma boton--chico marco__menu" aria-expanded={menuAbierto}
          aria-controls="navegacion" onClick={() => setMenuAbierto((a) => !a)}>Menú</button>
        <nav id="navegacion" className="nav" aria-label="Principal" data-abierto={menuAbierto}
          onClick={() => setMenuAbierto(false)}>
          {entradas.map((e) => (
            <NavLink key={e.ruta} to={e.ruta} end={e.ruta === '/'}>{e.texto}</NavLink>
          ))}
        </nav>
        <div className="usuario">
          <span><strong>{usuario}</strong> <span className="usuario__rol">· {nombreRol(roles)}</span></span>
          <button type="button" className="boton boton--chico" onClick={cerrarSesion}>Salir</button>
        </div>
      </header>
      <main id="contenido" className="marco__cuerpo" key={ubicacion.pathname}>
        <Outlet />
      </main>
      <footer className="marco__pie">
        <span>BioHub · Sistema Nacional de Registro y Vigilancia de Biodiversidad</span>
        <span>Pinturas de dominio público: F. E. Church, M. J. Heade</span>
      </footer>
    </div>
  );
}
