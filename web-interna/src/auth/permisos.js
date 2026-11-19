/**
 * Qué puede hacer cada rol en la web interna. Es copia de la matriz de 11-seguridad-y-acceso.md §2 (D63):
 * solo decide qué se muestra. Quien de verdad autoriza es el backend (gateway y @PreAuthorize en cada servicio).
 */
export const ROLES = {
  investigador_campo: 'Investigador de campo',
  auditor_cientifico: 'Auditor científico',
  cientifico_alto_rango: 'Científico de alto rango',
  analista_ambiental: 'Analista ambiental',
  administrador: 'Administrador',
};

const MATRIZ = {
  buscarHallazgos: ['investigador_campo', 'auditor_cientifico', 'cientifico_alto_rango', 'analista_ambiental', 'administrador'],
  verMapa: ['cientifico_alto_rango'],
  registrarHallazgo: ['investigador_campo', 'cientifico_alto_rango'],
  reclasificar: ['investigador_campo', 'cientifico_alto_rango'],
  editarPlantillas: ['investigador_campo', 'cientifico_alto_rango'],
  verHallazgo: ['investigador_campo', 'auditor_cientifico', 'cientifico_alto_rango', 'administrador'],
  verPlantillas: ['investigador_campo', 'auditor_cientifico', 'cientifico_alto_rango', 'administrador'],
  verHistorial: ['auditor_cientifico', 'cientifico_alto_rango', 'administrador'],
  equipos: ['administrador'],
  reportes: ['analista_ambiental', 'administrador'],
  usuarios: ['administrador'],
  apiKeys: ['administrador'],
};

export function puede(roles, capacidad) {
  return (MATRIZ[capacidad] ?? []).some((r) => roles.includes(r));
}

export function nombreRol(roles) {
  if (!roles.length) return 'Sin rol asignado';
  return roles.map((r) => ROLES[r] ?? r).join(', ');
}

/** Entradas del menú en orden; cada una aparece solo si el rol puede usarla. */
export const MENU = [
  { ruta: '/', texto: 'Inicio', capacidad: null },
  { ruta: '/buscar', texto: 'Hallazgos', capacidad: 'buscarHallazgos' },
  { ruta: '/hallazgos/nuevo', texto: 'Registrar', capacidad: 'registrarHallazgo' },
  { ruta: '/mapa', texto: 'Mapa', capacidad: 'verMapa' },
  { ruta: '/plantillas', texto: 'Fichas', capacidad: 'verPlantillas' },
  { ruta: '/equipos', texto: 'Equipos', capacidad: 'equipos' },
  { ruta: '/reportes', texto: 'Reportes', capacidad: 'reportes' },
  { ruta: '/usuarios', texto: 'Usuarios', capacidad: 'usuarios' },
  { ruta: '/api-keys', texto: 'API Keys', capacidad: 'apiKeys' },
];

export function menuPara(roles) {
  return MENU.filter((m) => m.capacidad === null || puede(roles, m.capacidad));
}
