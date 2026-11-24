import { act, cleanup, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { montar } from '../pruebas/montar.jsx';

vi.mock('../api/cliente.js', async (original) => ({
  ...(await original()),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), borrar: vi.fn(), subir: vi.fn() },
  pedir: vi.fn(() => new Promise(() => {})),
  descargar: vi.fn(),
  obtenerToken: vi.fn(() => Promise.resolve('token-de-prueba')),
}));

import { api, descargar } from '../api/cliente.js';

const LISTO = {
  id: 's-1', periodo: '2026-10', estado: 'LISTO', origen: 'PROGRAMADO', solicitadoPor: 'sistema', solicitadoEn: '2026-11-01T08:00:00Z',
  resumen: { totalHallazgos: 20, totalEnPeligro: 6, departamentos: 2, zonasEnAlerta: ['Chocó'], indices: [
    { departamento: 'Chocó', indice: 62.5, nivel: 'ALTO', enPeligro: 4, hallazgos: 5, promedioEnPeligroPrevio: 1, alerta: true },
    { departamento: 'Meta', indice: 10, nivel: 'BAJO', enPeligro: 0, hallazgos: 15, promedioEnPeligroPrevio: 0, alerta: false },
  ] },
};

describe('Reportes de presión (HU-09)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockResolvedValue([LISTO]);
  });
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it('abre el último reporte listo con sus cifras, la alerta y la tabla por departamento', async () => {
    montar('/reportes', ['analista_ambiental'], 'analista1');

    expect(await screen.findByRole('table', { name: 'Índice de presión por departamento' })).toBeInTheDocument();
    expect(screen.getByText('20')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Zonas en alerta: Chocó');
    expect(screen.getByRole('img', { name: 'Índice 62.5 de 100' })).toBeInTheDocument();
    expect(screen.getAllByText(/Octubre de 2026/i).length).toBeGreaterThan(0);
  });

  it('descarga el PDF y el CSV con nombres de archivo del periodo', async () => {
    const u = userEvent.setup();
    montar('/reportes', ['administrador'], 'admin1');

    await u.click(await screen.findByRole('button', { name: /Descargar PDF/ }));
    await u.click(screen.getByRole('button', { name: /Descargar CSV/ }));

    expect(descargar).toHaveBeenCalledWith('/api/reportes/solicitudes/s-1/pdf', 'presion-ecosistemas-2026-10.pdf');
    expect(descargar).toHaveBeenCalledWith('/api/reportes/solicitudes/s-1/csv', 'presion-ecosistemas-2026-10.csv');
  });

  it('pide un reporte y consulta su estado cada 2 s hasta que queda listo, sin bloquear', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const u = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const enProceso = { id: 's-2', periodo: '2026-09', estado: 'EN_PROCESO', origen: 'MANUAL', solicitadoPor: 'analista1',
      solicitadoEn: '2026-11-21T08:00:00Z', resumen: null };
    api.get.mockResolvedValueOnce([LISTO]).mockResolvedValueOnce([enProceso, LISTO])
      .mockResolvedValue([{ ...enProceso, estado: 'LISTO', resumen: { ...LISTO.resumen, totalHallazgos: 9 } }, LISTO]);
    api.post.mockResolvedValue(enProceso);
    montar('/reportes', ['analista_ambiental'], 'analista1');
    await screen.findByRole('table', { name: 'Índice de presión por departamento' });

    await u.clear(screen.getByLabelText('Mes'));
    await u.type(screen.getByLabelText('Mes'), '2026-09');
    await u.click(screen.getByRole('button', { name: /Generar reporte/ }));

    expect(api.post).toHaveBeenCalledWith('/api/reportes/solicitudes', { periodo: '2026-09' });
    expect(await screen.findByText('Generando el reporte en segundo plano…')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    expect(await screen.findByText('9')).toBeInTheDocument();
  });

  it('un reporte fallido explica por qué en vez de mostrar cifras', async () => {
    api.get.mockResolvedValue([{ ...LISTO, estado: 'FALLIDO', resumen: null, error: 'La base analítica no respondió.' }]);
    montar('/reportes', ['analista_ambiental']);

    expect(await screen.findByRole('alert')).toHaveTextContent('La base analítica no respondió.');
    expect(screen.queryByRole('button', { name: /Descargar PDF/ })).not.toBeInTheDocument();
  });

  it('el investigador de campo no entra a reportes', async () => {
    montar('/reportes', ['investigador_campo']);

    expect(await screen.findByRole('heading', { name: 'Esta sala no es para tu rol' })).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalledWith('/api/reportes/solicitudes');
  });
});
