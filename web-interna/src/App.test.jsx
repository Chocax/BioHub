import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App.jsx';
import { SesionContexto } from './componentes/Marco.jsx';
import { menuPara, puede } from './auth/permisos.js';

vi.mock('./api/cliente.js', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), borrar: vi.fn(), subir: vi.fn() },
  obtenerToken: vi.fn(() => Promise.resolve('token-de-prueba')),
  descargar: vi.fn(),
}));

import { api } from './api/cliente.js';

const PAGINA_VACIA = { content: [], page: 0, totalPaginas: 0, total: 0 };

function montar(ruta, roles, usuario = 'usuario1') {
  return render(
    <SesionContexto.Provider value={{ usuario, roles, cerrarSesion: vi.fn() }}>
      <MemoryRouter initialEntries={[ruta]}>
        <App alSesionVencida={vi.fn()} />
      </MemoryRouter>
    </SesionContexto.Provider>,
  );
}

const menu = () => within(screen.getByRole('navigation', { name: 'Principal' }));

describe('App (web interna)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockResolvedValue({ ...PAGINA_VACIA, total: 7 });
  });
  afterEach(cleanup);

  it('la portada saluda, muestra el total de hallazgos y el menú del rol', async () => {
    montar('/', ['investigador_campo'], 'investigador1');

    expect(await screen.findByText(/Hallazgos: 7/i)).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('/api/cientifico/ubicacion/hallazgos?page=0&size=1', expect.anything());
    expect(menu().getByRole('link', { name: 'Registrar' })).toBeInTheDocument();
    expect(menu().queryByRole('link', { name: 'Equipos' })).not.toBeInTheDocument();
    expect(menu().queryByRole('link', { name: 'Usuarios' })).not.toBeInTheDocument();
    expect(screen.getAllByText(/Investigador de campo/).length).toBeGreaterThan(0);
  });

  it('el administrador ve las salas de administración en el menú', () => {
    montar('/', ['administrador'], 'admin1');

    for (const sala of ['Equipos', 'Reportes', 'Usuarios', 'API Keys']) {
      expect(menu().getByRole('link', { name: sala })).toBeInTheDocument();
    }
    expect(menu().queryByRole('link', { name: 'Registrar' })).not.toBeInTheDocument();
  });

  it('una sala ajena al rol explica por qué y no llama a la API', async () => {
    montar('/usuarios', ['analista_ambiental']);

    expect(await screen.findByRole('heading', { name: 'Esta sala no es para tu rol' })).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalledWith(expect.stringContaining('/api/gateway/usuarios'));
  });

  it('el administrador abre el inventario de equipos', async () => {
    api.get.mockResolvedValue(PAGINA_VACIA);
    montar('/equipos', ['administrador'], 'admin1');

    expect(await screen.findByRole('heading', { name: /Equipos de/ })).toBeInTheDocument();
    expect(await screen.findByText('Sin equipos con estos filtros')).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('/api/equipos?page=0&size=15');
  });

  it('una ruta inexistente muestra la página de no encontrada', async () => {
    montar('/no-existe', ['investigador_campo']);

    expect(await screen.findByRole('heading', { name: /Esta sala/ })).toHaveTextContent('no existe');
  });
});

describe('matriz de permisos (11-seguridad-y-acceso §2)', () => {
  it('solo el alto rango ve el mapa con coordenadas exactas', () => {
    expect(puede(['cientifico_alto_rango'], 'verMapa')).toBe(true);
    for (const rol of ['investigador_campo', 'auditor_cientifico', 'analista_ambiental', 'administrador']) {
      expect(puede([rol], 'verMapa')).toBe(false);
    }
  });

  it('el analista no ve fichas ni detalle de hallazgos', () => {
    expect(puede(['analista_ambiental'], 'verHallazgo')).toBe(false);
    expect(puede(['analista_ambiental'], 'verPlantillas')).toBe(false);
    expect(menuPara(['analista_ambiental']).map((m) => m.texto)).toEqual(['Inicio', 'Hallazgos', 'Reportes']);
  });

  it('sin rol solo queda la portada', () => {
    expect(menuPara([]).map((m) => m.texto)).toEqual(['Inicio']);
  });
});
