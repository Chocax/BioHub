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

import { api, descargar, ErrorApi } from '../api/cliente.js';

const HALLAZGO = {
  id: 'h-1', version: 2, nombreCientifico: 'Ara macao', nombreComun: 'Guacamaya bandera', familia: 'Psittacidae',
  tipoEspecimen: 'Adulto', fechaRecoleccion: '2026-10-02', departamento: 'Meta', estadoConservacion: 'NO_AMENAZADA',
  categoriaUicn: 'LC', investigador: 'investigador1', descripcion: null, atributos: { envergadura: 92 },
};
const FOTO = { id: 'e-1', tipo: 'FOTOGRAFIA', nombre: 'ara.jpeg', tamanoBytes: 2048, subidoPor: 'investigador1', publica: false };
const AUDIO = { id: 'e-2', tipo: 'AUDIO', nombre: 'canto.mp3', tamanoBytes: 4096, subidoPor: 'investigador1', publica: false };

describe('Detalle del hallazgo', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    responderGet(api.get, [
      ['/api/cientifico/hallazgos/h-1/evidencias', [FOTO, AUDIO]],
      ['/api/cientifico/hallazgos/h-1', HALLAZGO],
      ['/api/cientifico/hallazgos/no-existe', new ErrorApi(404)],
      ['/api/cientifico/fichas/', { version: 1, atributos: [{ clave: 'envergadura', etiqueta: 'Envergadura', unidad: 'cm', tipo: 'NUMERO', habilitado: true }] }],
    ]);
  });
  afterEach(cleanup);

  it('muestra la ficha con las etiquetas y unidades de la plantilla, sin coordenadas', async () => {
    montar('/hallazgos/h-1', ['investigador_campo']);

    expect(await screen.findByText('Psittacidae')).toBeInTheDocument();
    expect(await screen.findByText('Envergadura')).toBeInTheDocument();
    expect(screen.getByText('92 cm')).toBeInTheDocument();
    expect(screen.getByText(/Las coordenadas no se muestran aquí/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/latitud|longitud/i);
  });

  it('quien registra decide qué foto sale en el portal; un audio no tiene esa opción (D65)', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({ ...FOTO, publica: true });
    montar('/hallazgos/h-1', ['investigador_campo']);

    const casillas = await screen.findAllByRole('checkbox', { name: 'Mostrar en el portal' });
    expect(casillas).toHaveLength(1);
    await u.click(casillas[0]);

    expect(api.put).toHaveBeenCalledWith('/api/cientifico/hallazgos/h-1/evidencias/e-1/publica', { publica: true });
    expect(await screen.findByText('Pública')).toBeInTheDocument();
  });

  it('el auditor ve y descarga evidencias, pero no reclasifica ni publica', async () => {
    const u = userEvent.setup();
    montar('/hallazgos/h-1', ['auditor_cientifico']);

    expect(await screen.findByText('ara.jpeg')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reclasificar/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: 'Mostrar en el portal' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Historial y versiones/ })).toHaveAttribute('href', '/hallazgos/h-1/historial');

    const fila = screen.getByText('canto.mp3').closest('li');
    await u.click(within(fila).getByRole('button', { name: /Descargar/ }));
    expect(descargar).toHaveBeenCalledWith('/api/cientifico/hallazgos/h-1/evidencias/e-2', 'canto.mp3');
  });

  it('reclasificar exige justificación y crea la versión siguiente (HU-03)', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({});
    montar('/hallazgos/h-1', ['cientifico_alto_rango']);

    await u.click(await screen.findByRole('button', { name: /Reclasificar/ }));
    expect(screen.getByText(/Se crea la versión 3/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Guardar nueva versión' }));
    expect(await screen.findByText('La justificación científica es obligatoria')).toBeInTheDocument();
    expect(api.put).not.toHaveBeenCalled();

    await u.type(screen.getByLabelText(/Justificación científica/), 'Análisis genético del laboratorio');
    await u.click(screen.getByRole('button', { name: 'Guardar nueva versión' }));

    expect(await screen.findByText('Nueva versión guardada.')).toBeInTheDocument();
    expect(api.put).toHaveBeenCalledWith('/api/cientifico/hallazgos/h-1', expect.objectContaining({
      nombreCientifico: 'Ara macao', justificacion: 'Análisis genético del laboratorio', atributos: { envergadura: 92 },
    }));
  });

  it('un hallazgo que no existe lo dice y ofrece volver a la búsqueda', async () => {
    montar('/hallazgos/no-existe', ['investigador_campo']);

    expect(await screen.findByText('Este hallazgo no existe')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a la búsqueda' })).toHaveAttribute('href', '/buscar');
  });
});
