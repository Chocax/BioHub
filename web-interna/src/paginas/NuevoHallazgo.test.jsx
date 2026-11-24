import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { montar, responderGet } from '../pruebas/montar.jsx';
import { refrescarSugerencias } from '../api/sugerencias.js';

vi.mock('../api/cliente.js', async (original) => ({
  ...(await original()),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), borrar: vi.fn(), subir: vi.fn() },
  pedir: vi.fn(() => new Promise(() => {})),
  descargar: vi.fn(),
  obtenerToken: vi.fn(() => Promise.resolve('token-de-prueba')),
}));

import { api, ErrorApi } from '../api/cliente.js';

const HALLAZGO = {
  id: 'h-1', version: 1, nombreCientifico: 'Oophaga histrionica', nombreComun: null, familia: 'Dendrobatidae',
  tipoEspecimen: 'Adulto', fechaRecoleccion: '2026-10-02', departamento: 'Chocó', estadoConservacion: 'EN_PELIGRO',
  categoriaUicn: 'EN', investigador: 'investigador1', atributos: {},
};

async function llenarObligatorios(u) {
  await u.type(screen.getByLabelText(/Nombre científico/), 'Oophaga histrionica');
  await u.type(screen.getByLabelText(/^Familia/), 'Dendrobatidae');
  await u.type(screen.getByLabelText(/Tipo de espécimen/), 'Adulto');
  fireEvent.change(screen.getByLabelText(/Fecha de recolección/), { target: { value: '2026-10-02' } });
  await u.selectOptions(screen.getByLabelText(/^Departamento/), 'Chocó');
}

describe('Registrar hallazgo (HU-01)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    refrescarSugerencias();
    URL.createObjectURL = vi.fn(() => 'blob:vista-previa');
    URL.revokeObjectURL = vi.fn();
    responderGet(api.get, [
      ['/api/cientifico/especies/sugerencias', { especies: [], familias: ['Dendrobatidae'], investigadores: [] }],
      ['/api/cientifico/fichas/', new ErrorApi(404)],
      ['/api/cientifico/hallazgos/h-1', HALLAZGO],
    ]);
  });
  afterEach(cleanup);

  it('sin los campos obligatorios muestra el mensaje del criterio y no llama a la API', async () => {
    const u = userEvent.setup();
    montar('/hallazgos/nuevo', ['investigador_campo'], 'investigador1');

    await u.click(await screen.findByRole('button', { name: 'Registrar hallazgo' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Campos obligatorios incompletos. Revisa los campos marcados.');
    expect(screen.getAllByText('Obligatorio').length).toBeGreaterThanOrEqual(4);
    expect(api.post).not.toHaveBeenCalled();
  });

  it('exige latitud y longitud juntas y no acepta fechas futuras', async () => {
    const u = userEvent.setup();
    montar('/hallazgos/nuevo', ['investigador_campo']);
    await llenarObligatorios(u);
    fireEvent.change(screen.getByLabelText(/Fecha de recolección/), { target: { value: '2999-01-01' } });
    await u.type(screen.getByLabelText(/^Latitud/), '5.5');

    await u.click(screen.getByRole('button', { name: 'Registrar hallazgo' }));

    expect(await screen.findByText('Escribe latitud y longitud, o ninguna')).toBeInTheDocument();
    expect(screen.getByText('No puede ser una fecha futura')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('registra con coordenadas, sube las evidencias válidas y abre el detalle', async () => {
    const u = userEvent.setup();
    api.post.mockResolvedValue({ hallazgoId: 'h-1' });
    api.subir.mockResolvedValue({});
    montar('/hallazgos/nuevo', ['investigador_campo'], 'investigador1');
    await llenarObligatorios(u);
    await u.type(screen.getByLabelText(/^Latitud/), '5.53214');
    await u.type(screen.getByLabelText(/^Longitud/), '-73.36712');
    const foto = new File(['foto'], 'rana.jpg', { type: 'image/jpeg' });
    const grande = new File(['x'], 'enorme.wav', { type: 'audio/wav' });
    Object.defineProperty(grande, 'size', { value: 10 * 1024 * 1024 });
    await u.upload(screen.getByLabelText(/Elegir archivos/), [foto, grande]);
    expect(screen.getByText('Supera 9 MB')).toBeInTheDocument();

    await u.click(screen.getByRole('button', { name: 'Registrar hallazgo' }));

    expect(await screen.findByText('Hallazgo registrado.')).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledWith('/api/cientifico/hallazgos', expect.objectContaining({
      nombreCientifico: 'Oophaga histrionica', departamento: 'Chocó', latitud: 5.53214, longitud: -73.36712,
      nombreComun: null, atributos: null,
    }));
    // Solo la foto: el audio de más de 9 MB no se envía
    expect(api.subir).toHaveBeenCalledTimes(1);
    expect(api.subir.mock.calls[0][0]).toBe('/api/cientifico/hallazgos/h-1/evidencias');
    expect(api.subir.mock.calls[0][1].get('archivo').name).toBe('rana.jpg');
  });

  it('muestra junto a cada campo los errores que devuelve el servidor', async () => {
    const u = userEvent.setup();
    api.post.mockRejectedValue(new ErrorApi(400, {
      detail: 'Datos inválidos', errores: [{ campo: 'familia', mensaje: 'La familia no existe en el catálogo' }],
    }));
    montar('/hallazgos/nuevo', ['investigador_campo']);
    await llenarObligatorios(u);

    await u.click(screen.getByRole('button', { name: 'Registrar hallazgo' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No se guardó el hallazgo');
    // El error queda pegado al campo (aria-describedby), no solo en el aviso de arriba
    expect(screen.getByLabelText(/^Familia/)).toHaveAccessibleDescription(/La familia no existe en el catálogo/);
    expect(screen.getByLabelText(/^Familia/)).toHaveAttribute('aria-invalid', 'true');
  });

  it('un rol que no registra no ve el formulario', async () => {
    montar('/hallazgos/nuevo', ['auditor_cientifico']);

    expect(await screen.findByRole('heading', { name: 'Esta sala no es para tu rol' })).toBeInTheDocument();
    await waitFor(() => expect(api.post).not.toHaveBeenCalled());
  });
});
