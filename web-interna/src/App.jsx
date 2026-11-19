import { lazy, Suspense, useMemo } from 'react';
import { Navigate, Route, Routes, useNavigate, useSearchParams } from 'react-router';
import { crearClienteUbicacion } from './ubicacion/api/ubicacionApi.js';
import { config } from './config.js';
import { obtenerToken } from './api/cliente.js';
import { puede } from './auth/permisos.js';
import Marco, { useSesion } from './componentes/Marco.jsx';
import { SinPermiso } from './componentes/ui.jsx';
import { Cargando } from './componentes/ui.jsx';
import Inicio from './paginas/Inicio.jsx';
import NoEncontrada from './paginas/NoEncontrada.jsx';

// Cada sala se carga cuando se visita: la portada abre rápido y Leaflet solo baja con el mapa o la búsqueda.
const PaginaBusqueda = lazy(() => import('./ubicacion/index.js').then((m) => ({ default: m.PaginaBusqueda })));
const PaginaMapa = lazy(() => import('./ubicacion/index.js').then((m) => ({ default: m.PaginaMapa })));
const NuevoHallazgo = lazy(() => import('./paginas/NuevoHallazgo.jsx'));
const DetalleHallazgo = lazy(() => import('./paginas/DetalleHallazgo.jsx'));
const Historial = lazy(() => import('./paginas/Historial.jsx'));
const Plantillas = lazy(() => import('./paginas/Plantillas.jsx'));
const Equipos = lazy(() => import('./paginas/Equipos.jsx'));
const Reportes = lazy(() => import('./paginas/Reportes.jsx'));
const Usuarios = lazy(() => import('./paginas/Usuarios.jsx'));
const ApiKeys = lazy(() => import('./paginas/ApiKeys.jsx'));

function Espera({ children }) {
  return <Suspense fallback={<section className="pagina"><Cargando /></section>}>{children}</Suspense>;
}

/** Muestra la página solo si el rol tiene la capacidad; si no, explica por qué (sin llamar a la API). */
function Con({ capacidad, children }) {
  const { roles } = useSesion();
  return puede(roles, capacidad) ? <Espera>{children}</Espera> : <SinPermiso />;
}

function Busqueda({ cliente, alSesionVencida }) {
  const { roles } = useSesion();
  const navegar = useNavigate();
  return (
    <PaginaBusqueda cliente={cliente} roles={roles} alSesionVencida={alSesionVencida}
      alVerEnMapa={puede(roles, 'verMapa') ? (id) => navegar(`/mapa?hallazgo=${encodeURIComponent(id)}`) : undefined}
      alVerDetalle={puede(roles, 'verHallazgo') ? (id) => navegar(`/hallazgos/${encodeURIComponent(id)}`) : undefined} />
  );
}

function Mapa({ cliente, alSesionVencida }) {
  const { roles } = useSesion();
  const [parametros] = useSearchParams();
  const navegar = useNavigate();
  return (
    <PaginaMapa cliente={cliente} roles={roles} hallazgoId={parametros.get('hallazgo')}
      alSesionVencida={alSesionVencida} alIrABusqueda={() => navegar('/buscar')} />
  );
}

/** Rutas de la web interna. El router y la sesión los pone quien monta la App (main.jsx o las pruebas). */
export default function App({ alSesionVencida }) {
  // Identidad estable: el módulo de ubicación necesita el mismo cliente en cada render (README de ubicacion).
  const cliente = useMemo(() => crearClienteUbicacion({ apiUrl: config.apiUrl, obtenerToken }), []);
  return (
    <Routes>
      <Route element={<Marco />}>
        <Route index element={<Inicio />} />
        <Route path="buscar" element={<Con capacidad="buscarHallazgos"><Busqueda cliente={cliente} alSesionVencida={alSesionVencida} /></Con>} />
        <Route path="mapa" element={<Espera><Mapa cliente={cliente} alSesionVencida={alSesionVencida} /></Espera>} />
        <Route path="hallazgos/nuevo" element={<Con capacidad="registrarHallazgo"><NuevoHallazgo /></Con>} />
        <Route path="hallazgos/:id" element={<Con capacidad="verHallazgo"><DetalleHallazgo /></Con>} />
        <Route path="hallazgos/:id/historial" element={<Con capacidad="verHistorial"><Historial /></Con>} />
        <Route path="plantillas" element={<Con capacidad="verPlantillas"><Plantillas /></Con>} />
        <Route path="equipos" element={<Con capacidad="equipos"><Equipos /></Con>} />
        <Route path="reportes" element={<Con capacidad="reportes"><Reportes /></Con>} />
        <Route path="usuarios" element={<Con capacidad="usuarios"><Usuarios /></Con>} />
        <Route path="api-keys" element={<Con capacidad="apiKeys"><ApiKeys /></Con>} />
        <Route path="ubicacion/busqueda" element={<Navigate to="/buscar" replace />} />
        <Route path="ubicacion/mapa" element={<Navigate to="/mapa" replace />} />
        <Route path="*" element={<NoEncontrada />} />
      </Route>
    </Routes>
  );
}
