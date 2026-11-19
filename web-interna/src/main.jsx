import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import App from './App.jsx';
import { cerrarSesion, iniciarSesion, keycloak, rolesUsuario } from './auth/keycloak.js';
import { SesionContexto } from './componentes/Marco.jsx';
import { Monograma } from './componentes/Ilustraciones.jsx';
import './estilos/galeria.css';

/** Pantalla mientras Keycloak confirma la sesión (o si no responde). */
function Puerta({ error }) {
  return (
    <main className="heroe" style={{ minHeight: '100vh', justifyContent: 'center', paddingTop: 0 }}>
      <div className="heroe__grupo">
        <Monograma className="monograma" />
        <p className="heroe__titulo" style={{ fontSize: 34 }}>BioHub</p>
        <p role={error ? 'alert' : 'status'} className="texto-suave">
          {error ?? 'Abriendo la galería…'}
        </p>
        {error && <button type="button" className="boton" onClick={() => window.location.reload()}>Reintentar</button>}
      </div>
    </main>
  );
}

function Raiz() {
  const [estado, setEstado] = useState({ listo: false, error: null });
  useEffect(() => {
    iniciarSesion()
      .then((autenticado) => setEstado({ listo: autenticado, error: autenticado ? null : 'No se pudo iniciar sesión.' }))
      .catch(() => setEstado({ listo: false, error: 'No se pudo conectar con el servidor de autenticación.' }));
  }, []);
  if (!estado.listo) return <Puerta error={estado.error} />;
  const sesion = {
    usuario: keycloak.tokenParsed?.preferred_username ?? '',
    roles: rolesUsuario(),
    cerrarSesion,
  };
  return (
    <SesionContexto.Provider value={sesion}>
      <BrowserRouter>
        <App alSesionVencida={() => keycloak.login()} />
      </BrowserRouter>
    </SesionContexto.Provider>
  );
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <Raiz />
  </StrictMode>,
);
