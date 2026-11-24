import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { montar, pagina, responderGet } from '../pruebas/montar.jsx';

vi.mock('../api/cliente.js', async (original) => ({
  ...(await original()),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), borrar: vi.fn(), subir: vi.fn() },
  pedir: vi.fn(() => new Promise(() => {})),
  descargar: vi.fn(),
  obtenerToken: vi.fn(() => Promise.resolve('token-de-prueba')),
}));

import { api, ErrorApi } from '../api/cliente.js';

const DRON = { id: 'q-1', nombre: 'Dron Mavic', codigoInventario: 'DRN-0042', categoria: 'Dron', estado: 'DISPONIBLE',
  custodioUsuario: null, asignadoEn: null };
const CAMARA = { id: 'q-2', nombre: 'Cámara trampa 7', codigoInventario: 'CAM-0007', categoria: 'Cámara trampa',
  estado: 'BAJA', custodioUsuario: null, asignadoEn: null };
const USUARIOS = pagina([
  { id: 'u-1', usuario: 'investigador1', nombre: 'Ivana', apellido: 'Investigadora', activo: true, roles: ['investigador_campo'] },
  { id: 'u-2', usuario: 'inactivo1', nombre: 'Iván', apellido: 'Inactivo', activo: false, roles: ['investigador_campo'] },
  { id: 'u-3', usuario: 'auditor1', nombre: 'Andrés', apellido: 'Auditor', activo: true, roles: ['auditor_cientifico'] },
]);

describe('Equipos de campo (HU-04)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    responderGet(api.get, [
      ['/api/gateway/usuarios', USUARIOS],
      [/\/api\/equipos\?estado=DISPONIBLE&page=0&size=1$/, { ...pagina([]), total: 4 }],
      [/\/api\/equipos\?estado=BAJA&page=0&size=1$/, { ...pagina([]), total: 1 }],
      ['/api/equipos?', pagina([DRON, CAMARA])],
    ]);
  });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); });

  it('cuenta los equipos por estado y lista el inventario', async () => {
    montar('/equipos', ['administrador'], 'admin1');

    expect(await screen.findByText('Dron Mavic')).toBeInTheDocument();
    const disponibles = (await screen.findByText('Disponibles')).closest('.indicador');
    expect(within(disponibles).getByText('4')).toBeInTheDocument();
    expect(screen.getByText('DRN-0042 · Dron')).toBeInTheDocument();
  });

  it('solo ofrece como custodios a investigadores de campo activos', async () => {
    montar('/equipos', ['administrador'], 'admin1');

    const selector = await screen.findByRole('combobox', { name: 'Custodio para Dron Mavic' });
    await screen.findAllByRole('option', { name: /investigador1/ });
    const opciones = within(selector).getAllByRole('option').map((o) => o.textContent);
    expect(opciones).toEqual(['Asignar a…', 'Ivana Investigadora (investigador1)']);
  });

  it('asigna un equipo al custodio elegido y confirma el cambio', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({ ...DRON, estado: 'ASIGNADO', custodioUsuario: 'investigador1' });
    montar('/equipos', ['administrador'], 'admin1');

    const asignar = within((await screen.findByText('Dron Mavic')).closest('tr')).getByRole('button', { name: /Asignar/ });
    expect(asignar).toBeDisabled();
    await screen.findAllByRole('option', { name: /investigador1/ });
    await u.selectOptions(screen.getByRole('combobox', { name: 'Custodio para Dron Mavic' }), 'u-1');
    await u.click(asignar);

    expect(api.put).toHaveBeenCalledWith('/api/equipos/q-1/asignacion', { custodioId: 'u-1' });
    expect(await screen.findByText('Dron Mavic: asignado a investigador1.')).toBeInTheDocument();
  });

  it('un equipo de baja ya no tiene acciones; dar de baja pide confirmación', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({ ...DRON, estado: 'BAJA' });
    montar('/equipos', ['administrador'], 'admin1');

    const filaBaja = (await screen.findByText('Cámara trampa 7')).closest('tr');
    expect(within(filaBaja).queryByRole('button')).not.toBeInTheDocument();

    await u.click(within(screen.getByText('Dron Mavic').closest('tr')).getByRole('button', { name: /Dar de baja/ }));
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('No se podrá volver a asignar'));
    expect(api.put).toHaveBeenCalledWith('/api/equipos/q-1/estado', { estado: 'BAJA' });
  });

  it('registra un equipo y muestra el error del servidor si el código ya existe', async () => {
    const u = userEvent.setup();
    api.post.mockRejectedValueOnce(new ErrorApi(409, { detail: 'Ya existe un equipo con ese código' }));
    montar('/equipos', ['administrador'], 'admin1');

    await u.type(await screen.findByLabelText(/Código de inventario/), 'DRN-0042');
    await u.type(screen.getByLabelText(/^Nombre/), 'Otro dron');
    await u.click(screen.getByRole('button', { name: /Registrar equipo/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe un equipo con ese código');
    expect(api.post).toHaveBeenCalledWith('/api/equipos', { codigoInventario: 'DRN-0042', nombre: 'Otro dron', categoria: 'Dron', descripcion: null });
  });
});
