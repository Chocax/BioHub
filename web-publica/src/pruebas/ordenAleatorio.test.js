import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ordenAleatorio } from '../utilidades/ordenAleatorio.js';

const OBRAS = ['nubes', 'tropico', 'andes', 'rio', 'chita', 'sangay'];
const id = (x) => x;

describe('ordenAleatorio', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('devuelve las mismas obras, sin perder ni repetir ninguna, y no cambia la lista original', () => {
    const copia = [...OBRAS];
    const orden = ordenAleatorio(OBRAS, 'prueba', id);
    expect([...orden].sort()).toEqual([...OBRAS].sort());
    expect(OBRAS).toEqual(copia);
  });

  it('no siempre arranca con la misma obra', () => {
    const primeras = new Set();
    for (let i = 0; i < 40; i += 1) {
      window.localStorage.clear();
      primeras.add(ordenAleatorio(OBRAS, 'prueba', id)[0]);
    }
    expect(primeras.size).toBeGreaterThan(2);
  });

  it('nunca repite la obra de arranque de la vez anterior', () => {
    let anterior = ordenAleatorio(OBRAS, 'prueba', id)[0];
    for (let i = 0; i < 30; i += 1) {
      const primera = ordenAleatorio(OBRAS, 'prueba', id)[0];
      expect(primera).not.toBe(anterior);
      anterior = primera;
    }
  });

  it('si el navegador no deja guardar, igual baraja', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('bloqueado'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('bloqueado'); });
    expect(ordenAleatorio(OBRAS, 'prueba', id)).toHaveLength(OBRAS.length);
  });
});
