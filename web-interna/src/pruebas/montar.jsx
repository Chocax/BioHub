// Ayudantes de las pruebas de las salas: montan la app completa en una ruta, con la sesión y el rol que se pidan,
// y responden las llamadas a la API según la ruta pedida. Cada archivo de prueba mockea '../api/cliente.js'.
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';
import App from '../App.jsx';
import { SesionContexto } from '../componentes/Marco.jsx';

export const PAGINA_VACIA = { content: [], page: 0, totalPaginas: 0, total: 0 };

export const pagina = (content) => ({ content, page: 0, totalPaginas: 1, total: content.length });

export function montar(ruta, roles, usuario = 'usuario1') {
  return render(
    <SesionContexto.Provider value={{ usuario, roles, cerrarSesion: vi.fn() }}>
      <MemoryRouter initialEntries={[typeof ruta === 'string' ? ruta : ruta]}>
        <App alSesionVencida={vi.fn()} />
      </MemoryRouter>
    </SesionContexto.Provider>,
  );
}

/**
 * Responde `api.get` según la ruta: `respuestas` es una lista de [prefijo o RegExp, valor o función(url)].
 * Un valor que es un Error se devuelve como promesa rechazada. Lo que no coincide responde una página vacía.
 */
export function responderGet(get, respuestas) {
  get.mockImplementation((url) => {
    for (const [patron, valor] of respuestas) {
      const coincide = patron instanceof RegExp ? patron.test(url) : url.startsWith(patron);
      if (coincide) {
        const r = typeof valor === 'function' ? valor(url) : valor;
        return r instanceof Error ? Promise.reject(r) : Promise.resolve(r);
      }
    }
    return Promise.resolve(PAGINA_VACIA);
  });
}
