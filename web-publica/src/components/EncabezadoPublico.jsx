import { Link } from 'react-router';
import { Monograma } from './Ilustraciones.jsx';

/** Barra mínima, igual en estructura a la de la web interna: marca a la izquierda, un solo enlace a la derecha. */
export default function EncabezadoPublico() {
  return (
    <header className="encabezado-publico">
      <Link to="/" className="marca" aria-label="BioHub, inicio">
        <Monograma />
        <span>BioHub</span>
      </Link>
      <p className="lema">Explora la biodiversidad de Colombia</p>
    </header>
  );
}
