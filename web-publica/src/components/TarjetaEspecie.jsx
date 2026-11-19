import { Link } from 'react-router';
import ChipEstado from './ChipEstado.jsx';
import FotoEspecie from './FotoEspecie.jsx';

/**
 * Tarjeta de una especie del catálogo: un solo enlace (un único tab stop) hacia su registro más reciente.
 * Solo departamento y región: nada parecido a una ubicación precisa.
 */
export default function TarjetaEspecie({ especie, volver = '' }) {
  const departamentos = especie.departamentos.join(', ');
  const lugar = [departamentos || null, especie.regiones.length ? `Región ${especie.regiones.join(', ')}` : null].filter(Boolean).join(' · ');
  return (
    <li className="tarjeta tarjeta-especie">
      <Link to={`/especies/${encodeURIComponent(especie.fichaId)}`} state={{ volver }} className="tarjeta-enlace">
        <FotoEspecie nombreCientifico={especie.nombreCientifico} className="tarjeta-foto" />
        <span className="tarjeta-cuerpo">
          <span className="tarjeta-titulo"><i lang="la">{especie.nombreCientifico}</i></span>
          {especie.nombreComun ? <span className="tarjeta-comun">{especie.nombreComun}</span> : null}
          <ChipEstado estado={especie.estadoConservacion} />
          {especie.familia ? <span>Familia {especie.familia}</span> : null}
          {lugar ? <span>{lugar}</span> : null}
          {especie.registros > 1 ? <span className="tarjeta-registros">{especie.registros} registros</span> : null}
        </span>
      </Link>
    </li>
  );
}
