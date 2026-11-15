import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useEffect, useRef } from 'react';
import { MapContainer, Marker, useMap } from 'react-leaflet';
import { etiquetaEstado, normalizarEstado } from '../utilidades/formato.js';
import TeselasGateway from './TeselasGateway.jsx';

export const CENTRO_COLOMBIA = [4.5, -74.1];
export const ZOOM_INICIAL = 5;
/** Máximo acercamiento: limita lo que las teselas pueden revelar (02 §7.3). Es el mismo tope del gateway (11 §7). */
export const ZOOM_MAXIMO = 12;
/** Zoom máximo del ajuste automático: con pocos puntos no se acerca tanto (SEC-J-04). */
export const ZOOM_AJUSTE_UN_PUNTO = 8;
export const ZOOM_AJUSTE_VARIOS = 10;
export const MAX_ERRORES_TESELAS = 3;
const ATRIBUCION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

// Iconos DOM (enfocables con teclado). Forma distinta por estado: no se depende solo del color.
const ICONOS = Object.fromEntries(
  ['EN_PELIGRO', 'NO_AMENAZADA', 'SIN_EVALUAR'].map((e) => [
    e,
    L.divIcon({ className: `ub-marcador ub-marcador--${e.toLowerCase().replace('_', '-')}`, html: '<span></span>', iconSize: [20, 20], iconAnchor: [10, 10] }),
  ]),
);

/**
 * Leaflet 1.9 no cancela, al quitar el mapa, el temporizador de 250 ms que cierra una animación de zoom:
 * si el mapa se desmonta a mitad de un zoom animado (p. ej. pasar a "Lista" mientras se ajusta la vista),
 * ese temporizador corre sobre un mapa ya quitado y lanza "Cannot read properties of undefined
 * (reading '_leaflet_pos')". Al desmontar se da la animación por terminada (`_animatingZoom` es interno de
 * Leaflet 1.9, como `_tiles` en CapaTeselasGateway), y `_onZoomTransitionEnd` sale sin hacer nada.
 */
function CerrarZoomAlQuitar() {
  const mapa = useMap();
  useEffect(() => () => {
    mapa._animatingZoom = false;
  }, [mapa]);
  return null;
}

/**
 * Leaflet hace enfocable el marcador (`keyboard: true`, tabindex 0 y role="button"), pero Enter sobre él
 * dispara `keydown`/`keypress` y no `click`. El diseño pide que Enter abra el detalle (02 §Teclado);
 * también se acepta Espacio, como en cualquier botón.
 */
export function esTeclaDeActivacion(evento) {
  return evento?.key === 'Enter' || evento?.key === ' ' || evento?.key === 'Spacebar';
}

function AjustarVista({ puntos }) {
  const mapa = useMap();
  useEffect(() => {
    if (puntos.length === 0) return;
    mapa.fitBounds(puntos.map((p) => p.posicion), { maxZoom: puntos.length === 1 ? ZOOM_AJUSTE_UN_PUNTO : ZOOM_AJUSTE_VARIOS, padding: [32, 32] });
  }, [puntos, mapa]);
  return null;
}

/**
 * Mapa Leaflet. Recibe puntos ya convertidos a [lat, lon]. Las teselas (datos de OpenStreetMap) se piden al
 * gateway con el JWT a través de `cliente.getBlob` (SEC-J-04): el navegador nunca contacta a OSM.
 * `alSesionVencida` se llama una vez si el gateway responde 401.
 */
export default function MapaHallazgos({ cliente, puntos, seleccionadoId, onSeleccionar, onTeselasFallidas, alSesionVencida }) {
  const errores = useRef(0);
  const alErrorTesela = () => {
    errores.current += 1;
    if (errores.current === MAX_ERRORES_TESELAS + 1) onTeselasFallidas?.();
  };
  return (
    <div role="region" aria-label="Mapa de hallazgos" className="ub-mapa">
      <p className="ub-solo-lectores">Mapa con {puntos.length} hallazgos</p>
      <MapContainer center={CENTRO_COLOMBIA} zoom={ZOOM_INICIAL} maxZoom={ZOOM_MAXIMO} minZoom={3} scrollWheelZoom keyboard style={{ height: '100%', width: '100%' }}>
        <TeselasGateway cliente={cliente} attribution={ATRIBUCION} maxZoom={ZOOM_MAXIMO} alErrorTesela={alErrorTesela} alSesionVencida={alSesionVencida} />
        <AjustarVista puntos={puntos} />
        <CerrarZoomAlQuitar />
        {puntos.map((p) => (
          <Marker
            key={p.id}
            position={p.posicion}
            icon={ICONOS[normalizarEstado(p.estadoConservacion)]}
            keyboard
            title={`${p.nombreCientifico} (${etiquetaEstado(p.estadoConservacion)})`}
            zIndexOffset={p.id === seleccionadoId ? 1000 : 0}
            eventHandlers={{
              click: (e) => onSeleccionar(p.id, e.target.getElement?.()),
              keydown: (e) => {
                if (!esTeclaDeActivacion(e.originalEvent)) return;
                e.originalEvent.preventDefault(); // Espacio no desplaza la página
                onSeleccionar(p.id, e.target.getElement?.());
              },
            }}
          />
        ))}
      </MapContainer>
    </div>
  );
}
