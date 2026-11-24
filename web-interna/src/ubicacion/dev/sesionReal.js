/**
 * Sesión real del arnés: usa el login de Santiago (src/auth/keycloak.js) sin modificarlo, igual que
 * lo hará App.jsx al montar las pantallas (README "Cómo montarlas").
 * El token vive solo en memoria (keycloak-js); el arnés no lo guarda ni lo muestra.
 */
import { cerrarSesion, iniciarSesion, keycloak, rolesUsuario } from '../../auth/keycloak.js';

export async function crearSesionReal() {
  const autenticado = await iniciarSesion(); // redirige al login de Keycloak si no hay sesión
  if (!autenticado) throw new Error('sin sesión');
  return {
    modo: 'real',
    usuario: keycloak.tokenParsed?.preferred_username ?? '',
    roles: rolesUsuario(),
    obtenerToken: async () => {
      await keycloak.updateToken(30);
      return keycloak.token;
    },
    alSesionVencida: () => keycloak.login(),
    cerrarSesion: () => cerrarSesion(),
  };
}
