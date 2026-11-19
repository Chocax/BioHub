// Obras de dominio público de public/pinturas (créditos en public/pinturas/CREDITOS.md): naturaleza de Colombia y
// los Andes, y escenas de la historia del país (expediciones, ríos, pueblos), sin temas políticos ni militares.
// `encuadre` es el object-position para los recortes (las obras verticales se recortan por el centro útil).
export const PINTURAS = {
  andes: { src: '/pinturas/andes.webp', chica: '/pinturas/andes-800.webp', titulo: 'El corazón de los Andes', autor: 'F. E. Church, 1859' },
  rio: { src: '/pinturas/rio.webp', chica: '/pinturas/rio-800.webp', titulo: 'El Río de Luz', autor: 'F. E. Church, 1877' },
  colibri: { src: '/pinturas/colibri.webp', chica: '/pinturas/colibri-800.webp', titulo: 'Orquídeas y colibrí', autor: 'M. J. Heade, c. 1880' },
  tequendama: { src: '/pinturas/tequendama.webp', chica: '/pinturas/tequendama-800.webp', titulo: 'El Salto del Tequendama', autor: 'F. E. Church, 1854', encuadre: '50% 45%' },
  lluvia: { src: '/pinturas/lluvia.webp', chica: '/pinturas/lluvia-800.webp', titulo: 'Temporada de lluvias en el trópico', autor: 'F. E. Church, 1866' },
  pasiflora: { src: '/pinturas/pasiflora.webp', chica: '/pinturas/pasiflora-800.webp', titulo: 'Pasifloras y colibríes', autor: 'M. J. Heade, c. 1875' },
};

const CLAVES = Object.keys(PINTURAS);

/** Una pintura al azar (distinta de `evitar`, si se da): cada vez que se abre una sala cambia la obra. */
export function pinturaAlAzar(evitar) {
  const opciones = CLAVES.filter((c) => c !== evitar);
  return opciones[Math.floor(Math.random() * opciones.length)];
}
