# web-interna

Web de trabajo de BioHub para el personal del Estado (React 19 + Vite, login con Keycloak por PKCE). Diseño "galería" (decisión D64 en `docs/equipo/03-arquitectura.md`).

## Arrancar

```powershell
npm install
npm run dev        # http://localhost:5173 (el puerto lo exige Keycloak)
npm test -- --run  # pruebas con Vitest
npm run test:e2e   # Playwright sobre el arnés de ubicación (D58)
npm run build
```

Variables (en `.env.development.local`, que no se sube): `VITE_API_URL` (gateway, por defecto `http://localhost:8080`), `VITE_KEYCLOAK_URL` (por defecto `http://localhost:8180`), `VITE_KEYCLOAK_REALM` y `VITE_KEYCLOAK_CLIENT_ID`.

## Cómo está organizada

| Carpeta | Qué tiene |
|---|---|
| `src/main.jsx` | Inicia sesión y monta el router con la sesión (usuario, roles, salir) |
| `src/App.jsx` | Rutas. Cada sala se carga cuando se visita y solo se muestra si el rol la tiene |
| `src/auth/permisos.js` | Qué puede hacer cada rol (copia de `11-seguridad-y-acceso.md` §2) y el menú. Solo decide qué se ve: quien autoriza es el backend |
| `src/api/cliente.js` | `api.get/post/put/borrar/subir` y `descargar`. Pone el token y convierte los errores del backend en `ErrorApi` con un mensaje legible |
| `src/componentes/` | Marco (barra, menú y pie), piezas de interfaz (`ui.jsx`), campos de atributos de las plantillas e ilustraciones SVG |
| `src/paginas/` | Una página por sala |
| `src/ubicacion/` | Módulo de búsqueda y mapa de Juanita, montado sin cambios de lógica |
| `src/estilos/galeria.css` | Todo el sistema visual: colores, tipografía, tarjetas con muescas, héroe y animaciones |
| `public/pinturas/` | Pinturas de dominio público en WebP; créditos en `CREDITOS.md` |

## Salas por rol

| Sala | Ruta | Roles |
|---|---|---|
| Inicio | `/` | todos |
| Hallazgos (búsqueda) | `/buscar` | todos |
| Mapa con coordenadas | `/mapa` | científico de alto rango |
| Registrar hallazgo | `/hallazgos/nuevo` | investigador de campo, alto rango |
| Detalle, evidencias y reclasificar | `/hallazgos/:id` | todos menos el analista (reclasificar: investigador y alto rango) |
| Historial y auditoría | `/hallazgos/:id/historial` | auditor, alto rango, administrador |
| Fichas (plantillas) | `/plantillas` | todos menos el analista (editar: investigador y alto rango) |
| Equipos de campo | `/equipos` | administrador |
| Reportes | `/reportes` | analista ambiental, administrador |
| Usuarios y roles | `/usuarios` | administrador |
| API Keys | `/api-keys` | administrador |
