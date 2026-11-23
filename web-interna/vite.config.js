import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/** Origen (esquema, host y puerto) de una URL, o el valor por defecto si no es válida. */
function origen(url, defecto) {
  try {
    return new URL(url || defecto).origin;
  } catch {
    return new URL(defecto).origin;
  }
}

/**
 * La CSP vive como <meta> en index.html. Los marcadores se reemplazan por el origen del gateway (VITE_API_URL) y
 * el de Keycloak (VITE_KEYCLOAK_URL), los mismos que usa src/config.js. En `vite dev` se quita la meta porque el
 * refresco en caliente necesita scripts en línea; la CSP aplica al build y al preview.
 */
function cspPlugin(modo) {
  const env = loadEnv(modo, process.cwd(), 'VITE_');
  const api = origen(env.VITE_API_URL, 'http://localhost:8080');
  const keycloak = origen(env.VITE_KEYCLOAK_URL, 'http://localhost:8180');
  return {
    name: 'biohub-csp',
    transformIndexHtml: {
      order: 'pre',
      handler(html, ctx) {
        if (ctx.server) return html.replace(/<!--\s*CSP de la web interna[\s\S]*?-->\s*<meta\s+http-equiv="Content-Security-Policy"[^>]*>\s*/, '');
        return html.replaceAll('%API_ORIGEN%', api).replaceAll('%KEYCLOAK_ORIGEN%', keycloak);
      },
    },
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [react(), cspPlugin(mode)],
  // El puerto debe coincidir con las redirectUris del cliente "web-interna" en Keycloak
  server: { port: 5173, strictPort: true },
  preview: { port: 5173, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
  },
}));
