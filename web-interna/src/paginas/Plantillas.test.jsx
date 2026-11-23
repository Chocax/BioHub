import { cleanup, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { montar, pagina, responderGet } from '../pruebas/montar.jsx';
import { refrescarSugerencias } from '../api/sugerencias.js';
import { claveDesde } from './Plantillas.jsx';

vi.mock('../api/cliente.js', async (original) => ({
  ...(await original()),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), borrar: vi.fn(), subir: vi.fn() },
  pedir: vi.fn(() => new Promise(() => {})),
  descargar: vi.fn(),
  obtenerToken: vi.fn(() => Promise.resolve('token-de-prueba')),
}));

import { api, ErrorApi } from '../api/cliente.js';

const ESPECIE = { nombreCientifico: 'Ara macao', nombreComun: 'Guacamaya bandera', familia: 'Psittacidae', registros: 3,
  fichaVersion: 2, atributosFicha: 1, departamentos: ['Meta'], estadoConservacion: 'NO_AMENAZADA', foto: null };
const FICHA = { version: 2, creadoPor: 'investigador1', creadoEn: '2026-11-01T10:00:00Z', atributos: [
  { clave: 'envergadura', etiqueta: 'Envergadura', tipo: 'NUMERO', obligatorio: false, habilitado: true, unidad: 'cm' }] };

describe('Fichas taxonómicas (HU-02, RNF8)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    refrescarSugerencias();
    responderGet(api.get, [
      ['/api/cientifico/especies/sugerencias', { especies: [], familias: ['Psittacidae'], investigadores: [] }],
      ['/api/cientifico/especies', pagina([ESPECIE])],
      ['/api/cientifico/fichas/Ara%20macao/atributos', FICHA],
      ['/api/cientifico/fichas/', new ErrorApi(404)],
    ]);
  });
  afterEach(cleanup);

  it('la clave interna sale de la etiqueta, sin tildes ni espacios', () => {
    expect(claveDesde('Temperatura del agua')).toBe('temperatura_del_agua');
    expect(claveDesde('  Longitud (mm) ')).toBe('longitud_mm');
    expect(claveDesde('2 Patas')).toBe('patas');
    expect(claveDesde('ñandú')).toBe('nandu');
  });

  it('muestra una tarjeta por especie con su ficha y filtra por familia', async () => {
    const u = userEvent.setup();
    montar('/plantillas', ['auditor_cientifico']);

    expect(await screen.findByRole('button', { name: 'Abrir la ficha de Ara macao' })).toHaveTextContent('Ficha v2 · 1 atributo');
    await u.selectOptions(await screen.findByLabelText('Familia'), 'Psittacidae');
    expect(api.get).toHaveBeenLastCalledWith('/api/cientifico/especies?page=0&size=12&familia=Psittacidae');
  });

  it('el auditor ve la ficha pero no la puede cambiar', async () => {
    const u = userEvent.setup();
    montar('/plantillas', ['auditor_cientifico']);

    await u.click(await screen.findByRole('button', { name: 'Abrir la ficha de Ara macao' }));

    expect(await screen.findByText('Envergadura')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Agregar atributo/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Guardar nueva versión/ })).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Activo: Envergadura' })).toBeDisabled();
  });

  it('agregar un atributo no le quita el foco al escribir y guarda una versión nueva', async () => {
    const u = userEvent.setup();
    api.put.mockResolvedValue({ ...FICHA, version: 3, atributos: [...FICHA.atributos,
      { clave: 'color_del_pico', etiqueta: 'Color del pico', tipo: 'TEXTO', obligatorio: false, habilitado: true, unidad: null }] });
    montar('/plantillas?especie=Ara%20macao', ['investigador_campo']);

    await u.click(await screen.findByRole('button', { name: /Agregar atributo/ }));
    const campo = screen.getByRole('textbox', { name: 'Nombre del atributo 2' });
    await u.type(campo, 'Color del pico');
    expect(campo).toHaveFocus();
    expect(campo).toHaveValue('Color del pico');
    expect(screen.getByText('color_del_pico')).toBeInTheDocument();

    await u.click(screen.getByRole('button', { name: /Guardar nueva versión/ }));

    expect(await screen.findByText(/Ficha guardada como versión 3/)).toBeInTheDocument();
    expect(api.put).toHaveBeenCalledWith('/api/cientifico/fichas/Ara%20macao/atributos', [
      { clave: 'envergadura', etiqueta: 'Envergadura', tipo: 'NUMERO', obligatorio: false, habilitado: true, unidad: 'cm' },
      { clave: 'color_del_pico', etiqueta: 'Color del pico', tipo: 'TEXTO', obligatorio: false, habilitado: true, unidad: null },
    ]);
  });

  it('un atributo guardado no se puede quitar, solo desactivar; uno sin nombre no se guarda', async () => {
    const u = userEvent.setup();
    montar('/plantillas?especie=Ara%20macao', ['investigador_campo']);

    expect(await screen.findByRole('checkbox', { name: 'Activo: Envergadura' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /Quitar/ })).not.toBeInTheDocument();

    await u.click(screen.getByRole('button', { name: /Agregar atributo/ }));
    await u.click(screen.getByRole('button', { name: /Guardar nueva versión/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Cada atributo necesita un nombre.');
    expect(api.put).not.toHaveBeenCalled();
  });

  it('una especie sin ficha invita a crear la primera versión', async () => {
    montar('/plantillas?especie=Atelopus%20ebenoides', ['investigador_campo']);

    expect(await screen.findByText(/todavía no tiene ficha: crea la primera versión/)).toBeInTheDocument();
    expect(screen.getByText('Sin atributos todavía.')).toBeInTheDocument();
  });
});
