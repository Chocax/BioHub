import { Link } from 'react-router';
import { useTitulo, Vacio } from '../componentes/ui.jsx';

export default function NoEncontrada() {
  const ref = useTitulo('Página no encontrada');
  return (
    <section className="pagina">
      <h1 className="titulo-pagina" tabIndex={-1} ref={ref}>Esta sala <em>no existe</em></h1>
      <Vacio titulo="La ruta que buscas no está en la galería">
        <Link className="boton" to="/">Volver al inicio</Link>
      </Vacio>
    </section>
  );
}
