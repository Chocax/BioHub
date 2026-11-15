import { useEffect, useRef } from 'react';
import { useMap } from 'react-leaflet';
import { CapaTeselasGateway, RUTA_TESELAS } from './CapaTeselasGateway.js';

/**
 * Fondo del mapa: teselas por el gateway con JWT (SEC-J-04). Debe ir dentro de <MapContainer>.
 * `alErrorTesela` se llama por cada tesela que falla (no por las abortadas);
 * `alSesionVencida` una sola vez si el gateway responde 401.
 */
export default function TeselasGateway({ cliente, attribution, maxZoom, alErrorTesela, alSesionVencida }) {
  const mapa = useMap();
  const refError = useRef(alErrorTesela);
  const refSesion = useRef(alSesionVencida);
  refError.current = alErrorTesela;
  refSesion.current = alSesionVencida;

  useEffect(() => {
    const capa = new CapaTeselasGateway(RUTA_TESELAS, {
      cliente,
      attribution,
      maxZoom,
      alSesionVencida: () => refSesion.current?.(),
    });
    capa.on('tileerror', () => refError.current?.());
    capa.addTo(mapa);
    return () => {
      if (mapa.hasLayer(capa)) mapa.removeLayer(capa); // quita las teselas: aborta y revoca las pendientes
    };
  }, [mapa, cliente, attribution, maxZoom]);

  return null;
}
