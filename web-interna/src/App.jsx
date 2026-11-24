import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { useSugerencias } from './api/sugerencias.js';
import { FotoEvidencia } from './componentes/Foto.jsx';
import { PINTURAS } from './componentes/pinturas.js';
import { Link, Navigate, Route, Routes, useNavigate, useSearchParams } from 'react-router';
import { crearClienteUbicacion } from './ubicacion/api/ubicacionApi.js';
import { config } from './config.js';
import { api, obtenerToken } from './api/cliente.js';
import { puede } from './auth/permisos.js';
import Marco, { usePinturaSala, useSesion } from './componentes/Marco.jsx';
import { Cargando, SinPermiso } from './componentes/ui.jsx';
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

/** Las listas del módulo de ubicación solo necesitan los nombres. */
function useSugerenciasUbicacion() {
  const s = useSugerencias();
  return useMemo(() => (s ? { especies: s.especies.map((e) => e.nombreCientifico), investigadores: s.investigadores } : undefined), [s]);
}

/** Foto del hallazgo (prefiere la marcada como pública) y enlace a su ficha, dentro del panel del mapa. */
function ExtraDetalleMapa({ hallazgoId }) {
  const [foto, setFoto] = useState(undefined);
  useEffect(() => {
    let vigente = true;
    api.get(`/api/cientifico/hallazgos/${hallazgoId}/evidencias`)
      .then((lista) => {
        const fotos = lista.filter((e) => e.tipo === 'FOTOGRAFIA');
        const elegida = fotos.find((e) => e.publica) ?? fotos[fotos.length - 1];
        if (vigente) setFoto(elegida ? { hallazgoId, evidenciaId: elegida.id } : null);
      })
      .catch(() => { if (vigente) setFoto(null); });
    return () => { vigente = false; };
  }, [hallazgoId]);
  return (
    <>
      <div className="detalle-mapa__foto">{foto !== undefined && <FotoEvidencia foto={foto} alt="Foto del hallazgo" />}</div>
      <Link className="detalle-mapa__enlace" to={`/hallazgos/${encodeURIComponent(hallazgoId)}`}>
        Abrir ficha completa <ArrowRight className="icono" aria-hidden="true" />
      </Link>
    </>
  );
}

function Busqueda({ cliente, alSesionVencida }) {
  const { roles } = useSesion();
  const navegar = useNavigate();
  const sugerencias = useSugerenciasUbicacion();
  // La pintura junto al título del módulo de ubicación también cambia en cada visita (variable CSS, ver galeria.css).
  const pintura = PINTURAS[usePinturaSala()] ?? PINTURAS.rio;
  return (
    <div className="envoltura-busqueda" style={{ '--pintura-busqueda': `url('${pintura.chica}')`, '--encuadre-busqueda': pintura.encuadre ?? '50% 50%' }}>
    <PaginaBusqueda cliente={cliente} roles={roles} alSesionVencida={alSesionVencida} sugerencias={sugerencias}
      alVerEnMapa={puede(roles, 'verMapa') ? (id) => navegar(`/mapa?hallazgo=${encodeURIComponent(id)}`) : undefined}
      alVerDetalle={puede(roles, 'verHallazgo') ? (id) => navegar(`/hallazgos/${encodeURIComponent(id)}`) : undefined} />
    </div>
  );
}

function Mapa({ cliente, alSesionVencida }) {
  const { roles } = useSesion();
  const [parametros] = useSearchParams();
  const navegar = useNavigate();
  const sugerencias = useSugerenciasUbicacion();
  return (
    <PaginaMapa cliente={cliente} roles={roles} hallazgoId={parametros.get('hallazgo')} sugerencias={sugerencias}
      detalleExtra={(punto) => <ExtraDetalleMapa key={punto.id} hallazgoId={punto.id} />}
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
