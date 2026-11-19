import { useEffect, useState } from 'react';
import { api } from './cliente.js';

// Especies, familias e investigadores ya registrados (GET /api/cientifico/especies/sugerencias, D65). Se pide una
// vez por sesión y se comparte entre salas; `refrescarSugerencias` la vuelve a pedir tras registrar un hallazgo.
let promesa = null;

function cargar() {
  if (!promesa) {
    promesa = api.get('/api/cientifico/especies/sugerencias').catch((e) => { promesa = null; throw e; });
  }
  return promesa;
}

export function refrescarSugerencias() {
  promesa = null;
}

/** null mientras carga o si falla: los campos siguen funcionando como texto libre. */
export function useSugerencias() {
  const [datos, setDatos] = useState(null);
  useEffect(() => {
    let vigente = true;
    cargar().then((d) => { if (vigente) setDatos(d); }).catch(() => {});
    return () => { vigente = false; };
  }, []);
  return datos;
}
