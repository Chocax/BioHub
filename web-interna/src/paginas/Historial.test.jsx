import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { montar, responderGet } from '../pruebas/montar.jsx';

vi.mock('../api/cliente.js', async (original) => ({
  ...(await original()),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), borrar: vi.fn(), subir: vi.fn() },
  pedir: vi.fn(() => new Promise(() => {})),
  descargar: vi.fn(),
  obtenerToken: vi.fn(() => Promise.resolve('token-de-prueba')),
}));

import { api } from '../api/cliente.js';

const VERSIONES = [
  { id: 'v3', version: 3, creadoEn: '2026-11-03T10:00:00Z', autor: 'cientifico1', justificacion: 'Revisión del museo',
    snapshot: { nombreCientifico: 'Oophaga histrionica' } },
  { id: 'v2', version: 2, creadoEn: '2026-11-02T10:00:00Z', autor: 'cientifico1', justificacion: 'Análisis genético',
    snapshot: { nombreCientifico: 'Oophaga lehmanni' } },
  { id: 'v1', version: 1, creadoEn: '2026-11-01T10:00:00Z', autor: 'investigador1', justificacion: null,
    snapshot: { nombreCientifico: 'Oophaga lehmanni' } },
];
const AUDITORIA = [{ fecha: '2026-11-03T10:00:00Z', campo: 'nombreCientifico', valorPrevio: 'Oophaga lehmanni',
  valorNuevo: 'Oophaga histrionica', justificacion: 'Revisión del museo', autor: 'cientifico1' }];

describe('Historial del hallazgo (HU-05)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    responderGet(api.get, [
      ['/api/cientifico/hallazgos/h-1/versiones', VERSIONES],
      ['/api/cientifico/hallazgos/h-1/auditoria', AUDITORIA],
      [/\/comparar\?desde=2&hasta=3$/, { versionAnterior: 2, versionNueva: 3, diferencias: [
        { campo: 'nombreCientifico', valorAnterior: 'Oophaga lehmanni', valorNuevo: 'Oophaga histrionica' }] }],
      [/\/comparar\?desde=1&hasta=3$/, { versionAnterior: 1, versionNueva: 3, diferencias: [] }],
    ]);
  });
  afterEach(cleanup);

  it('lista las versiones de la más nueva a la más vieja, con autor y justificación', async () => {
    montar('/hallazgos/h-1/historial', ['auditor_cientifico']);

    const linea = await screen.findByRole('list', { name: 'Versiones del hallazgo' });
    const items = within(linea).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent('Versión 3');
    expect(items[0]).toHaveTextContent('Revisión del museo');
    expect(items[2]).toHaveTextContent('Registro inicial');
    expect(items[2]).toHaveTextContent('Por investigador1');
  });

  it('compara por defecto las dos últimas versiones y deja elegir otras', async () => {
    const u = userEvent.setup();
    montar('/hallazgos/h-1/historial', ['auditor_cientifico']);

    const tabla = await screen.findByRole('table', { name: 'Campos que cambiaron' });
    expect(within(tabla).getByText('Oophaga lehmanni').tagName).toBe('DEL');
    expect(within(tabla).getByText('Oophaga histrionica').tagName).toBe('INS');

    await u.selectOptions(screen.getByLabelText('Desde'), '1');
    expect(await screen.findByText('Sin diferencias entre estas versiones.')).toBeInTheDocument();

    await u.selectOptions(screen.getByLabelText('Desde'), '3');
    expect(screen.getByText('Elige dos versiones distintas.')).toBeInTheDocument();
  });

  it('muestra la auditoría campo a campo y no ofrece editar ni borrar nada', async () => {
    montar('/hallazgos/h-1/historial', ['auditor_cientifico']);

    expect(await screen.findByText('nombreCientifico', { selector: 'td' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Editar|Borrar|Eliminar/ })).not.toBeInTheDocument();
    expect(api.put).not.toHaveBeenCalled();
    expect(api.borrar).not.toHaveBeenCalled();
  });

  it('el investigador de campo no entra al historial', async () => {
    montar('/hallazgos/h-1/historial', ['investigador_campo']);

    expect(await screen.findByRole('heading', { name: 'Esta sala no es para tu rol' })).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalledWith(expect.stringContaining('/versiones'));
  });
});
