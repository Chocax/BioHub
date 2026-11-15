import { defineConfig, devices } from '@playwright/test';

/**
 * E2E del módulo de ubicación sobre el arnés de desarrollo (D58), en modo simulado.
 * - Sin backend: el gateway se intercepta con page.route (src/ubicacion/e2e/fixtures.js).
 * - Los specs se llaman *.e2e.js para que Vitest (include por defecto **\/*.{test,spec}.*) no los ejecute;
 *   así no hace falta tocar vite.config.js.
 * - RNF13: Chromium, Firefox y WebKit, como web-publica. E2E_SIN_WEBKIT=1 omite WebKit en equipos sin
 *   sus librerías del sistema (sudo npx playwright install-deps webkit).
 * - Puerto propio (5175) para no reutilizar por error un `npm run dev` abierto en 5173.
 */
const PUERTO = 5175;
const API_URL = 'http://localhost:8080';

const proyectos = [
  { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
];
if (process.env.E2E_SIN_WEBKIT !== '1') proyectos.push({ name: 'webkit', use: { ...devices['Desktop Safari'] } });

export default defineConfig({
  testDir: './src/ubicacion/e2e',
  testMatch: '**/*.e2e.js',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  // Las trazas guardan cabeceras, DOM y capturas, y la CI las sube si algo falla. Aquí solo hay token
  // y datos simulados. Una E2E contra Keycloak o datos reales debe usar trace: 'off' y no subir
  // artefactos (SEC-J-I30).
  use: { baseURL: `http://localhost:${PUERTO}`, locale: 'es-CO', trace: 'retain-on-failure' },
  projects: proyectos,
  webServer: {
    // Servidor de desarrollo de Vite: el arnés solo existe ahí (no está en el build).
    command: `npx vite --port ${PUERTO} --strictPort`,
    url: `http://localhost:${PUERTO}/src/ubicacion/dev/arnes.html`,
    // La URL del gateway queda fija para que las pruebas sepan a qué origen deben ir las peticiones.
    env: { VITE_API_URL: API_URL },
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
