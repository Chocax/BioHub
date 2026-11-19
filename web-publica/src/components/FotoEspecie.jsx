import { useState } from 'react';
import { urlFotoEspecie } from '../api/especies.js';
import { Mariposa } from './Ilustraciones.jsx';

/**
 * Foto pública de la especie (la que eligió un investigador, D65) o, si no hay, una ilustración: nunca un hueco.
 * La foto ya no trae metadatos ni la ubicación GPS.
 */
export default function FotoEspecie({ nombreCientifico, className = 'foto-especie', prioridad = false }) {
  const [falla, setFalla] = useState(false);
  if (!nombreCientifico || falla) {
    return (
      <div className={`${className} foto-especie--vacia`} role="img" aria-label="Esta especie todavía no tiene foto pública">
        <Mariposa className="foto-especie__ilustracion" />
      </div>
    );
  }
  return (
    <div className={className}>
      <img src={urlFotoEspecie(nombreCientifico)} alt={`Fotografía de ${nombreCientifico}`}
        loading={prioridad ? 'eager' : 'lazy'} onError={() => setFalla(true)} />
    </div>
  );
}
