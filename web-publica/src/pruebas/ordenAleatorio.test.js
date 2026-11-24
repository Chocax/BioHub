import { beforeEach, describe, expect, it } from 'vitest';
import { ordenAleatorio } from '../utilidades/ordenAleatorio.js';

const OBRAS = ['nubes', 'tropico', 'andes', 'rio', 'chita', 'sangay'];

describe('ordenAleatorio (portal)', () => {
  beforeEach(() => { window.localStorage.clear(); window.sessionStorage.clear(); });

  it('devuelve las mismas obras, sin perder ni repetir ninguna, y no cambia la lista original', () => {
    const copia = [...OBRAS];
    const orden = ordenAleatorio(OBRAS);
    expect([...orden].sort()).toEqual([...OBRAS].sort());
    expect(OBRAS).toEqual(copia);
  });

  it('no siempre arranca con la misma obra', () => {
    const primeras = new Set(Array.from({ length: 40 }, () => ordenAleatorio(OBRAS)[0]));
    expect(primeras.size).toBeGreaterThan(2);
  });

  it('no guarda nada en el navegador (WP-08)', () => {
    ordenAleatorio(OBRAS);
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });
});
