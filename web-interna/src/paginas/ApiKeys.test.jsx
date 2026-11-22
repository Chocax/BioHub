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

import { api } from '../api/cliente.js';

const CONSUMIDOR = { id: 'c-1', nombre: 'Universidad de los Andes', contactoEmail: 'datos@uniandes.test',
  creadoEn: '2026-11-01T10:00:00Z', limitePorMinuto: null, limiteEfectivo: 60 };
const KEYS = [
  { id: 'k-1', prefijo: 'bh_live_ab12', estado: 'ACTIVA', ultimoUsoEn: null, expiraEn: null },
  { id: 'k-2', prefijo: 'bh_live_zz99', estado: 'REVOCADA', ultimoUsoEn: '2026-11-10T10:00:00Z', expiraEn: null },
];

describe('API Keys de consumidores externos (HU-12, RNF11)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    responderGet(api.get, [
      ['/api/gateway/consumidores/c-1/keys', KEYS],
      ['/api/gateway/consumidores?', pagina([CONSUMIDOR])],
    ]);
  });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); });

  it('abre el primer consumidor con sus keys; solo una activa se puede revocar', async () => {
    montar('/api-keys', ['administrador'], 'admin1');

    expect(await screen.findByText('bh_live_ab12…')).toBeInTheDocument();
    const activa = screen.getByText('bh_live_ab12…').closest('tr');
    const revocada = screen.getByText('bh_live_zz99…').closest('tr');
    expect(within(activa).getByRole('button', { name: /Revocar/ })).toBeInTheDocument();
    expect(within(revocada).queryByRole('button', { name: /Revocar/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Límite por minuto', { selector: '#limite' })).toHaveAttribute('placeholder', '60 (por defecto)');
  });

  it('una key nueva se muestra una sola vez y se puede ocultar', async () => {
    const u = userEvent.setup();
    api.post.mockResolvedValue({ id: 'k-3', apiKey: 'bh_live_secreta_123' });
    montar('/api-keys', ['administrador'], 'admin1');

    await u.click(await screen.findByRole('button', { name: /Emitir nueva key/ }));

    expect(api.post).toHaveBeenCalledWith('/api/gateway/consumidores/c-1/keys', undefined);
    expect(await screen.findByText('bh_live_secreta_123')).toBeInTheDocument();
    expect(screen.getByText('Copia la key ahora: no se vuelve a mostrar')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Ya la guardé' }));
    expect(screen.queryByText('bh_live_secreta_123')).not.toBeInTheDocument();
  });

  it('revocar pide confirmación y avisa que aplica desde la siguiente petición', async () => {
    const u = userEvent.setup();
    api.borrar.mockResolvedValue({});
    montar('/api-keys', ['administrador'], 'admin1');

    const activa = (await screen.findByText('bh_live_ab12…')).closest('tr');
    await u.click(within(activa).getByRole('button', { name: /Revocar/ }));

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('bh_live_ab12'));
    expect(api.borrar).toHaveBeenCalledWith('/api/gateway/keys/k-1');
    expect(await screen.findByText('Key revocada: se rechaza desde la siguiente petición.')).toBeInTheDocument();
  });

  it('cambia el límite por minuto; vacío vuelve al valor por defecto', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({});
    montar('/api-keys', ['administrador'], 'admin1');

    const limite = await screen.findByLabelText('Límite por minuto', { selector: '#limite' });
    await u.type(limite, '120');
    await u.click(screen.getByRole('button', { name: /Guardar límite/ }));
    expect(api.put).toHaveBeenCalledWith('/api/gateway/consumidores/c-1/limite', { limitePorMinuto: 120 });
    expect(await screen.findByText('Límite actualizado; aplica de inmediato.')).toBeInTheDocument();

    await u.clear(limite);
    await u.click(screen.getByRole('button', { name: /Guardar límite/ }));
    expect(api.put).toHaveBeenLastCalledWith('/api/gateway/consumidores/c-1/limite', { limitePorMinuto: null });
  });

  it('crea un consumidor con el límite como número', async () => {
    const u = userEvent.setup();
    api.post.mockResolvedValue({ ...CONSUMIDOR, id: 'c-2', nombre: 'ONG Selva' });
    montar('/api-keys', ['administrador'], 'admin1');

    await u.type(await screen.findByLabelText(/Universidad u ONG/), 'ONG Selva');
    await u.type(screen.getByLabelText(/Correo de contacto/), 'datos@selva.test');
    await u.type(screen.getAllByLabelText(/Límite por minuto/)[0], '30');
    await u.click(screen.getByRole('button', { name: /Crear consumidor/ }));

    expect(api.post).toHaveBeenCalledWith('/api/gateway/consumidores', {
      nombre: 'ONG Selva', contactoEmail: 'datos@selva.test', limitePorMinuto: 30,
    });
  });

  it('solo el administrador entra', async () => {
    montar('/api-keys', ['cientifico_alto_rango']);

    expect(await screen.findByRole('heading', { name: 'Esta sala no es para tu rol' })).toBeInTheDocument();
  });
});
