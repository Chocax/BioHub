import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import FotoEspecie from '../components/FotoEspecie.jsx';

const ID = '9b1d4c2e-7a3f-4d5b-8c6e-1f2a3b4c5d6e';
const ok = () => ({ ok: true, status: 200, blob: () => Promise.resolve(new Blob(['x'], { type: 'image/jpeg' })) });
const falla = (status) => ({ ok: false, status, blob: () => Promise.resolve(new Blob()) });

describe('FotoEspecie (D66)', () => {
  let revocadas;
  beforeEach(() => {
    revocadas = [];
    let n = 0;
    URL.createObjectURL = () => `blob:foto-${++n}`;
    URL.revokeObjectURL = (u) => revocadas.push(u);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('sin fotoId no hace ninguna petición y dice que no hay foto pública', () => {
    const f = vi.fn();
    vi.stubGlobal('fetch', f);
    render(<FotoEspecie nombreCientifico="Ara macao" fotoId={null} />);
    expect(screen.getByRole('img', { name: 'Esta especie todavía no tiene foto pública' })).toBeInTheDocument();
    expect(f).not.toHaveBeenCalled();
  });

  it('200: pide /fotos/{id} sin credenciales y muestra la imagen; revoca al desmontar', async () => {
    const f = vi.fn(() => Promise.resolve(ok()));
    vi.stubGlobal('fetch', f);
    const { unmount } = render(<FotoEspecie nombreCientifico="Ara macao" fotoId={ID} />);
    const img = await screen.findByAltText('Fotografía de Ara macao');
    expect(img).toHaveAttribute('src', 'blob:foto-1');
    expect(String(f.mock.calls[0][0])).toMatch(new RegExp(`/api/publica/especies/fotos/${ID}$`));
    expect(f.mock.calls[0][1].credentials).toBe('omit');
    expect(revocadas).toEqual([]);
    unmount();
    expect(revocadas).toEqual(['blob:foto-1']);
  });

  it('404: ilustración con "sin foto pública"', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(falla(404))));
    render(<FotoEspecie nombreCientifico="Ara macao" fotoId={ID} />);
    expect(await screen.findByRole('img', { name: 'Esta especie todavía no tiene foto pública' })).toBeInTheDocument();
  });

  it('429: texto neutro (sin afirmar que no hay foto) y reintento que recupera la foto', async () => {
    const f = vi.fn().mockResolvedValueOnce(falla(429)).mockResolvedValue(ok());
    vi.stubGlobal('fetch', f);
    const u = userEvent.setup();
    render(<FotoEspecie nombreCientifico="Ara macao" fotoId={ID} reintentable />);
    expect(await screen.findByText('Foto no disponible por ahora')).toBeInTheDocument();
    expect(screen.queryByText(/no tiene foto pública/)).not.toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByAltText('Fotografía de Ara macao')).toBeInTheDocument();
    expect(f).toHaveBeenCalledTimes(2);
  });

  it('red caída o 503: mismo texto neutro; en tarjetas no hay botón', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('red'))));
    render(<FotoEspecie nombreCientifico="Ara macao" fotoId={ID} />);
    expect(await screen.findByText('Foto no disponible por ahora')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('al cambiar de fotoId revoca la URL anterior, no la que está en uso', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(ok())));
    const { rerender } = render(<FotoEspecie nombreCientifico="A b" fotoId={ID} />);
    await screen.findByAltText('Fotografía de A b');
    rerender(<FotoEspecie nombreCientifico="A b" fotoId="11111111-2222-4333-8444-555555555555" />);
    await waitFor(() => expect(screen.getByAltText('Fotografía de A b')).toHaveAttribute('src', 'blob:foto-2'));
    expect(revocadas).toEqual(['blob:foto-1']);
  });
});
