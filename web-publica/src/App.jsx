import { Link, Route, Routes } from 'react-router';
import EncabezadoPublico from './components/EncabezadoPublico.jsx';
import EnlaceSaltar from './components/EnlaceSaltar.jsx';
import { Monograma } from './components/Ilustraciones.jsx';
import Ficha from './paginas/Ficha.jsx';
import Inicio from './paginas/Inicio.jsx';
import NoEncontrada from './paginas/NoEncontrada.jsx';

/** Rutas del portal. El router (BrowserRouter / MemoryRouter) lo pone quien monta la App. */
export default function App() {
  return (
    <>
      <EnlaceSaltar />
      <EncabezadoPublico />
      <main id="contenido">
        <Routes>
          <Route path="/" element={<Inicio />} />
          <Route path="/especies/:id" element={<Ficha />} />
          <Route path="*" element={<NoEncontrada tipo="pagina" />} />
        </Routes>
      </main>
      <footer className="pie-publico">
        <div className="pie-publico__marca">
          <span className="pie-publico__logo"><Monograma /> BioHub</span>
          <p>Sistema Nacional de Registro y Vigilancia de Biodiversidad. Datos abiertos de las expediciones del Instituto,
            con las ubicaciones de especies en peligro protegidas.</p>
        </div>
        <nav className="pie-publico__columna" aria-label="Explora">
          <p className="pie-publico__titulo">Explora</p>
          <Link to="/" onClick={() => window.scrollTo({ top: 0 })}>Buscar una especie</Link>
          <Link to="/" onClick={() => window.scrollTo({ top: 0 })}>Catálogo de la A a la Z</Link>
        </nav>
        <div className="pie-publico__columna">
          <p className="pie-publico__titulo">Para investigadores</p>
          <p>Universidades y ONG pueden consultar estos datos por la API externa, con una llave y límites por minuto.</p>
          <p>El personal del Instituto entra por la plataforma interna.</p>
        </div>
        <div className="pie-publico__columna">
          <p className="pie-publico__titulo">Créditos</p>
          <p>Pinturas de dominio público: F. E. Church, M. J. Heade, círculo de J. Constable y la Comisión Corográfica.</p>
          <p>Fotografías de las expediciones, publicadas por quien las registró.</p>
        </div>
        <p className="pie-publico__legal">© {new Date().getFullYear()} BioHub · Proyecto académico, Universidad El Bosque</p>
      </footer>
    </>
  );
}
