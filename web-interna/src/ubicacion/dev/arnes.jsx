/**
 * Entrada del arnés de desarrollo del módulo de ubicación (D58). NO es parte de la aplicación:
 * la carga solo `arnes.html`, que `vite build` no incluye (su única entrada es index.html).
 *
 * Modos:
 *  - real (predeterminado): login con Keycloak usando src/auth/keycloak.js sin modificarlo.
 *  - simulado (`?modo=simulado&roles=...`): token y roles falsos para Playwright. Solo existe bajo
 *    `import.meta.env.DEV`; en un build de producción esa rama se elimina (y esta página ni siquiera
 *    se construye). La API sigue siendo `config.apiUrl`: las pruebas la interceptan con `page.route`.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import Arnes from './Arnes.jsx';
import './arnes.css';

const raiz = createRoot(document.getElementById('arnes'));

function mostrarMensaje(texto, rol = 'status') {
  raiz.render(
    <main className="arnes-contenido">
      <p role={rol} className={rol === 'alert' ? 'arnes-error' : undefined}>{texto}</p>
    </main>,
  );
}

async function iniciar() {
  if (!import.meta.env.DEV) {
    // Defensa en profundidad: si alguien llegara a construir esta página, no hace nada.
    mostrarMensaje('El arnés de ubicación solo funciona en desarrollo.', 'alert');
    return;
  }
  const parametros = new URLSearchParams(window.location.search);
  let sesion;
  if (import.meta.env.DEV && parametros.get('modo') === 'simulado') {
    const { crearSesionSimulada } = await import('./sesionSimulada.js');
    sesion = crearSesionSimulada(parametros);
  } else {
    mostrarMensaje('Iniciando sesión…');
    try {
      const { crearSesionReal } = await import('./sesionReal.js');
      sesion = await crearSesionReal();
    } catch {
      mostrarMensaje('No se pudo conectar con el servidor de autenticación. ¿Está Keycloak levantado?', 'alert');
      return;
    }
  }
  raiz.render(
    <StrictMode>
      <Arnes
        sesion={sesion}
        pantallaInicial={parametros.get('pantalla') === 'mapa' ? 'mapa' : 'busqueda'}
        hallazgoInicial={parametros.get('hallazgo')}
      />
    </StrictMode>,
  );
}

iniciar();
