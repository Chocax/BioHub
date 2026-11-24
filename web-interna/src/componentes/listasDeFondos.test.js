// Las tres rotaciones de pantalla completa (carrusel del inicio interno, cielo del portal y fondo del login) deben
// mostrar las mismas obras. Esta prueba lee las tres listas y comprueba que coincidan y que exista cada archivo.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { FONDOS_GRANDES, PINTURAS } from './pinturas.js';

const RAIZ = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const repo = (ruta) => join(RAIZ, ruta);

const delPortal = () => {
  const codigo = readFileSync(repo('web-publica/src/paginas/Inicio.jsx'), 'utf8');
  const bloque = codigo.slice(codigo.indexOf('export const FONDOS = ['), codigo.indexOf('];', codigo.indexOf('export const FONDOS = [')));
  return [...bloque.matchAll(/chica: '\/pinturas\/([a-z-]+)-800\.webp'/g)].map((m) => m[1]);
};
const delLogin = () => {
  const codigo = readFileSync(repo('infra/keycloak/themes/biohub/login/resources/js/fondos.js'), 'utf8');
  const bloque = codigo.slice(codigo.indexOf('var FONDOS = ['), codigo.indexOf('];', codigo.indexOf('var FONDOS = [')));
  return [...bloque.matchAll(/'([a-z-]+)'/g)].map((m) => m[1]);
};
const ordenadas = (l) => [...l].sort();

describe('obras de pantalla completa', () => {
  it('son 20 y todas están en el registro de pinturas', () => {
    expect(FONDOS_GRANDES).toHaveLength(20);
    expect(new Set(FONDOS_GRANDES).size).toBe(20);
    for (const n of FONDOS_GRANDES) expect(PINTURAS[n], n).toBeDefined();
  });

  it('el portal y el login muestran exactamente las mismas que el carrusel interno', () => {
    expect(ordenadas(delPortal())).toEqual(ordenadas(FONDOS_GRANDES));
    expect(ordenadas(delLogin())).toEqual(ordenadas(FONDOS_GRANDES));
  });

  it('cada obra tiene su archivo en las dos webs y en el tema del login', () => {
    for (const n of FONDOS_GRANDES) {
      expect(existsSync(repo(`web-interna/public${PINTURAS[n].src}`)), `interna ${n}`).toBe(true);
      expect(existsSync(repo(`web-interna/public${PINTURAS[n].chica}`)), `interna ${n}-800`).toBe(true);
      expect(existsSync(repo(`web-publica/public/pinturas/${n}-800.webp`)), `portal ${n}-800`).toBe(true);
      expect(existsSync(repo(`web-publica/public/pinturas/${n}-1600.webp`)), `portal ${n}-1600`).toBe(true);
      expect(existsSync(repo(`infra/keycloak/themes/biohub/login/resources/img/${n}.webp`)), `login ${n}`).toBe(true);
    }
  });
});
