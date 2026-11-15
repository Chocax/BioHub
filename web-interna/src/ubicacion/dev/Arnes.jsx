import { useCallback, useMemo, useState } from 'react';
import { config } from '../../config.js';
import { crearClienteUbicacion, PaginaBusqueda, PaginaMapa } from '../index.js';

/**
 * Arnés de desarrollo (D58): monta las pantallas del módulo igual que lo hará Santiago en App.jsx,
 * pero con una navegación mínima propia (sin router) entre Búsqueda y Mapa.
 * `sesion`: { modo: 'real' | 'simulado', usuario, roles, obtenerToken, alSesionVencida, cerrarSesion }.
 */
export default function Arnes({ sesion, pantallaInicial = 'busqueda', hallazgoInicial = null }) {
  const [pantalla, setPantalla] = useState({ nombre: pantallaInicial, hallazgoId: pantallaInicial === 'mapa' ? hallazgoInicial : null });
  const [sesionesVencidas, setSesionesVencidas] = useState(0);

  // Identidad estable: se crea una sola vez por sesión (README "Cómo montarlas").
  const cliente = useMemo(
    () => crearClienteUbicacion({ apiUrl: config.apiUrl, obtenerToken: sesion.obtenerToken }),
    [sesion],
  );

  const alSesionVencida = useCallback(() => {
    setSesionesVencidas((n) => n + 1);
    sesion.alSesionVencida?.();
  }, [sesion]);

  const irABusqueda = useCallback(() => setPantalla({ nombre: 'busqueda', hallazgoId: null }), []);
  const irAMapa = useCallback((hallazgoId = null) => setPantalla({ nombre: 'mapa', hallazgoId }), []);
  const simulado = sesion.modo === 'simulado';

  return (
    <>
      <header className="arnes-cabecera">
        <p className="arnes-aviso" role="note" aria-label="Solo desarrollo">
          <strong>Solo desarrollo.</strong> Arnés del módulo de ubicación: no forma parte del build ni de la
          aplicación. La navegación real la monta Santiago en App.jsx.
        </p>
        <div className="arnes-barra">
          <p className="arnes-sesion">
            Modo: <strong data-testid="arnes-modo">{simulado ? 'simulado (sin Keycloak)' : 'real (Keycloak)'}</strong>
            {' · '}Usuario: <span>{sesion.usuario || '—'}</span>
            {' · '}Roles: <span data-testid="arnes-roles">{sesion.roles.length ? sesion.roles.join(', ') : 'ninguno'}</span>
          </p>
          <nav aria-label="Pantallas del módulo de ubicación" className="arnes-nav">
            <button type="button" aria-current={pantalla.nombre === 'busqueda' ? 'page' : undefined} onClick={irABusqueda}>
              Búsqueda
            </button>
            <button type="button" aria-current={pantalla.nombre === 'mapa' ? 'page' : undefined} onClick={() => irAMapa(null)}>
              Mapa
            </button>
            {sesion.cerrarSesion ? (
              <button type="button" onClick={sesion.cerrarSesion}>Cerrar sesión</button>
            ) : null}
          </nav>
        </div>
        {simulado && sesionesVencidas > 0 ? (
          <p role="status" className="arnes-aviso" data-testid="arnes-sesion-vencida">
            Se pidió iniciar sesión de nuevo ({sesionesVencidas}). En modo real se llamaría a keycloak.login().
          </p>
        ) : null}
      </header>
      <main className="arnes-contenido">
        {pantalla.nombre === 'mapa' ? (
          <PaginaMapa
            cliente={cliente}
            roles={sesion.roles}
            hallazgoId={pantalla.hallazgoId}
            alSesionVencida={alSesionVencida}
            alIrABusqueda={irABusqueda}
          />
        ) : (
          <PaginaBusqueda
            cliente={cliente}
            roles={sesion.roles}
            alSesionVencida={alSesionVencida}
            alVerEnMapa={(id) => irAMapa(id)}
          />
        )}
      </main>
    </>
  );
}
