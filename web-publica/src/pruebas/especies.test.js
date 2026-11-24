import { describe, expect, it } from 'vitest';
import { aEspeciePublica } from '../api/especies.js';

const UUID = '9b1d4c2e-7a3f-4d5b-8c6e-1f2a3b4c5d6e';
const base = { nombreCientifico: 'Ara macao', fichaId: UUID };

describe('aEspeciePublica', () => {
  it('conserva un fotoId con forma de UUID', () => {
    expect(aEspeciePublica({ ...base, fotoId: UUID }).fotoId).toBe(UUID);
  });
  it.each([[null], [undefined], [''], ['abc'], [123], [`${UUID}/../x`], ['../fotos'], [{}], [`${UUID} `]])(
    'fotoId inválido (%s) pasa a null', (v) => {
      expect(aEspeciePublica({ ...base, fotoId: v }).fotoId).toBeNull();
    });
  it('no deja pasar claves fuera de la lista blanca', () => {
    const e = aEspeciePublica({ ...base, latitud: 1, investigador: 'x', fotoId: UUID });
    expect(Object.keys(e)).not.toContain('latitud');
    expect(Object.keys(e)).not.toContain('investigador');
  });
});
