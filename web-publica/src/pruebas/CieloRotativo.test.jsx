import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CieloRotativo } from '../paginas/Inicio.jsx';

const movimiento = (reducido) =>
  vi.stubGlobal('matchMedia', (q) => ({ matches: reducido && q.includes('reduce'), addEventListener() {}, removeEventListener() {} }));

describe('CieloRotativo', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it('renderiza solo la actual y la siguiente, con srcset de 800, 1600 y grande', () => {
    movimiento(false);
    const { container } = render(<CieloRotativo />);
    const imgs = container.querySelectorAll('img');
    expect(imgs).toHaveLength(2);
    expect(imgs[0].dataset.activa).toBe('true');
    expect(imgs[1].dataset.activa).toBe('false');
    expect(imgs[0].getAttribute('srcset')).toMatch(/nubes-800\.webp 800w, \/pinturas\/nubes-1600\.webp 1600w, \/pinturas\/nubes-grande\.webp 2400w/);
  });

  it('a los 25 s pasa a la siguiente, deja la anterior durante el fundido y luego la quita', () => {
    movimiento(false);
    const { container } = render(<CieloRotativo />);
    act(() => { vi.advanceTimersByTime(25000); });
    let imgs = [...container.querySelectorAll('img')];
    expect(imgs).toHaveLength(3);
    expect(imgs.find((i) => i.dataset.activa === 'true').getAttribute('src')).toContain('tropico');
    act(() => { vi.advanceTimersByTime(3300); });
    imgs = [...container.querySelectorAll('img')];
    expect(imgs).toHaveLength(2);
  });

  it('con movimiento reducido hay una sola pintura y no rota', () => {
    movimiento(true);
    const { container } = render(<CieloRotativo />);
    act(() => { vi.advanceTimersByTime(60000); });
    const imgs = container.querySelectorAll('img');
    expect(imgs).toHaveLength(1);
    expect(imgs[0].getAttribute('src')).toContain('nubes');
  });
});
