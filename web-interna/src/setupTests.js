import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';

// Las pruebas de las salas montan la app completa (rutas, marco, carga diferida). Con la máquina cargada (CI, o
// Maven corriendo al lado) el primer render puede pasar de 1 s, la espera por defecto de findBy*/waitFor.
configure({ asyncUtilTimeout: 3000 });
