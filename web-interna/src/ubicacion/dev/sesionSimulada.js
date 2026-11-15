/**
 * Sesión SIMULADA del arnés, solo para Playwright (D58). No contacta a Keycloak: el token es una cadena
 * falsa y los roles vienen de la URL (`?modo=simulado&roles=cientifico_alto_rango`).
 * Solo se importa bajo `import.meta.env.DEV` desde arnes.jsx, y arnes.html no entra en el build.
 *
 * Parámetros de la URL:
 *  - roles: lista separada por comas (se filtran como rolesUsuario(): solo los 5 de negocio). Vacío = sin rol.
 *  - usuario: nombre que se muestra en la cabecera del arnés (por defecto "usuario-simulado").
 *  - token=vencido: obtenerToken() rechaza, como cuando keycloak.updateToken falla (la API no se llama).
 */
export const TOKEN_SIMULADO = 'token-simulado-e2e';

const ROLES_BIOHUB = [
  'investigador_campo',
  'auditor_cientifico',
  'cientifico_alto_rango',
  'analista_ambiental',
  'administrador',
];

export function crearSesionSimulada(parametros) {
  if (!import.meta.env.DEV) throw new Error('La sesión simulada solo existe en desarrollo.');
  const roles = (parametros.get('roles') ?? '')
    .split(',')
    .map((r) => r.trim())
    .filter((r) => ROLES_BIOHUB.includes(r));
  const tokenVencido = parametros.get('token') === 'vencido';
  return {
    modo: 'simulado',
    usuario: parametros.get('usuario') || 'usuario-simulado',
    roles,
    obtenerToken: async () => {
      if (tokenVencido) throw new Error('token vencido (simulado)');
      return TOKEN_SIMULADO;
    },
    alSesionVencida: () => {}, // en modo real sería keycloak.login(); el arnés lo muestra en pantalla
    cerrarSesion: null,
  };
}
