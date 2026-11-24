import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { montar, pagina } from '../pruebas/montar.jsx';

vi.mock('../api/cliente.js', async (original) => ({
  ...(await original()),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), borrar: vi.fn(), subir: vi.fn() },
  pedir: vi.fn(() => new Promise(() => {})),
  descargar: vi.fn(),
  obtenerToken: vi.fn(() => Promise.resolve('token-de-prueba')),
}));

import { api } from '../api/cliente.js';

const USUARIOS = pagina([
  { id: 'u-0', usuario: 'admin1', nombre: 'Admin', apellido: 'BioHub', email: 'admin1@biohub.test', activo: true, roles: ['administrador'] },
  { id: 'u-1', usuario: 'investigador1', nombre: 'Ivana', apellido: 'Investigadora', email: 'i1@biohub.test', activo: true, roles: ['investigador_campo'] },
  { id: 'u-2', usuario: 'baja1', nombre: 'Berta', apellido: 'Baja', email: 'b1@biohub.test', activo: false, roles: ['analista_ambiental'] },
  { id: 'u-3', usuario: 'sinrol1', nombre: 'Simón', apellido: 'Sinrol', email: 's1@biohub.test', activo: true, roles: [] },
]);

const fila = (usuario) => screen.getByText(new RegExp(`^${usuario} ·`)).closest('tr');

describe('Usuarios y roles (HU-10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    api.get.mockResolvedValue(USUARIOS);
  });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); });

  it('cuenta activos, inactivos y sin rol, y marca al inactivo', async () => {
    montar('/usuarios', ['administrador'], 'admin1');

    await screen.findByText(/^investigador1 ·/);
    const valor = (texto) => within(screen.getByText(texto, { selector: '.indicador__texto' }).closest('.indicador'))
      .getByText(/\d+/).textContent;
    expect(valor('Usuarios')).toBe('4');
    expect(valor('Activos')).toBe('3');
    expect(valor('Inactivos')).toBe('1');
    expect(valor('Sin rol')).toBe('1');
    expect(fila('baja1')).toHaveClass('usuarios__fila--inactiva');
    expect(within(fila('baja1')).getByText('Inactivo')).toBeInTheDocument();
  });

  it('los filtros muestran solo los inactivos o solo un rol', async () => {
    const u = userEvent.setup();
    montar('/usuarios', ['administrador'], 'admin1');
    await screen.findByText(/^investigador1 ·/);

    await u.click(screen.getByRole('button', { name: /^Inactivos/ }));
    expect(screen.getByRole('button', { name: /^Inactivos/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText(/^investigador1 ·/)).not.toBeInTheDocument();
    expect(screen.getByText(/^baja1 ·/)).toBeInTheDocument();

    await u.click(screen.getByRole('button', { name: /^Sin rol/ }));
    expect(screen.getByText(/^sinrol1 ·/)).toBeInTheDocument();
    expect(screen.queryByText(/^baja1 ·/)).not.toBeInTheDocument();
  });

  it('nadie se cambia el rol ni se desactiva a sí mismo', async () => {
    montar('/usuarios', ['administrador'], 'admin1');
    await screen.findByText(/^admin1 ·/);

    expect(screen.getByRole('combobox', { name: 'Rol de admin1' })).toBeDisabled();
    expect(within(fila('admin1')).queryByRole('button', { name: /Desactivar/ })).not.toBeInTheDocument();
    expect(within(fila('admin1')).getByText('Tú')).toBeInTheDocument();
  });

  it('cambiar el rol llama a la API y avisa que sus sesiones se cerraron; quitarlo usa DELETE', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({});
    api.borrar.mockResolvedValue({});
    montar('/usuarios', ['administrador'], 'admin1');
    await screen.findByText(/^investigador1 ·/);

    await u.selectOptions(screen.getByRole('combobox', { name: 'Rol de investigador1' }), 'cientifico_alto_rango');
    expect(api.put).toHaveBeenCalledWith('/api/gateway/usuarios/u-1/rol', { rol: 'cientifico_alto_rango' });
    expect(await screen.findByText(/Sus sesiones abiertas se cerraron/)).toBeInTheDocument();

    await u.selectOptions(screen.getByRole('combobox', { name: 'Rol de baja1' }), '');
    expect(api.borrar).toHaveBeenCalledWith('/api/gateway/usuarios/u-2/rol');
  });

  it('desactivar pide confirmación; activar no', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({});
    montar('/usuarios', ['administrador'], 'admin1');
    await screen.findByText(/^investigador1 ·/);

    await u.click(within(fila('investigador1')).getByRole('button', { name: /Desactivar/ }));
    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(api.put).toHaveBeenCalledWith('/api/gateway/usuarios/u-1/estado', { activo: false });

    await u.click(within(fila('baja1')).getByRole('button', { name: /Activar/ }));
    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(api.put).toHaveBeenCalledWith('/api/gateway/usuarios/u-2/estado', { activo: true });
  });

  it('al crear un usuario muestra una sola vez su contraseña temporal', async () => {
    const u = userEvent.setup();
    api.post.mockResolvedValue({ id: 'u-9', contrasenaTemporal: 'Temporal-1234-abcd' });
    montar('/usuarios', ['administrador'], 'admin1');
    await screen.findByText(/^investigador1 ·/);

    await u.type(screen.getByLabelText(/^Usuario/), 'nuevo1');
    await u.type(screen.getByLabelText(/^Correo/), 'nuevo1@biohub.test');
    await u.type(screen.getByLabelText(/^Nombre/), 'Nora');
    await u.type(screen.getByLabelText(/^Apellido/), 'Nueva');
    await u.click(screen.getByRole('radio', { name: 'Auditor científico' }));
    await u.click(screen.getByRole('button', { name: /Crear usuario/ }));

    expect(api.post).toHaveBeenCalledWith('/api/gateway/usuarios', {
      usuario: 'nuevo1', email: 'nuevo1@biohub.test', nombre: 'Nora', apellido: 'Nueva', rol: 'auditor_cientifico',
    });
    expect(await screen.findByText('Temporal-1234-abcd')).toBeInTheDocument();
    expect(screen.getByText('Solo se muestra esta vez')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Listo' }));
    expect(screen.queryByText('Temporal-1234-abcd')).not.toBeInTheDocument();
  });
});
