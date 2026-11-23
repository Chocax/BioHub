// Obras de dominio público de public/pinturas (créditos en public/pinturas/CREDITOS.md): naturaleza de Colombia y
// los Andes, y escenas de la historia del país (expediciones, ríos, pueblos), sin temas políticos ni militares.
// Las claves con guion van entre comillas; el nombre del archivo es la misma clave.
// `encuadre` es el object-position para los recortes (las obras verticales se recortan por el centro útil).
export const PINTURAS = {
  andes: { src: '/pinturas/andes.webp', chica: '/pinturas/andes-800.webp', titulo: 'El corazón de los Andes', autor: 'F. E. Church, 1859' },
  rio: { src: '/pinturas/rio.webp', chica: '/pinturas/rio-800.webp', titulo: 'El Río de Luz', autor: 'F. E. Church, 1877' },
  colibri: { src: '/pinturas/colibri.webp', chica: '/pinturas/colibri-800.webp', titulo: 'Orquídeas y colibrí', autor: 'M. J. Heade, c. 1880' },
  tequendama: { src: '/pinturas/tequendama.webp', chica: '/pinturas/tequendama-800.webp', titulo: 'El Salto del Tequendama', autor: 'F. E. Church, 1854', encuadre: '50% 45%' },
  lluvia: { src: '/pinturas/lluvia.webp', chica: '/pinturas/lluvia-800.webp', titulo: 'Temporada de lluvias en el trópico', autor: 'F. E. Church, 1866' },
  pasiflora: { src: '/pinturas/pasiflora.webp', chica: '/pinturas/pasiflora-800.webp', titulo: 'Pasifloras y colibríes', autor: 'M. J. Heade, c. 1875' },
  // Bocetos al óleo del viaje de Church por Colombia (1853) y Ecuador (Cooper Hewitt, Smithsonian; CC0)
  'tequendama-cima': { src: '/pinturas/tequendama-cima.webp', chica: '/pinturas/tequendama-cima-800.webp', titulo: 'Cima del Salto del Tequendama', autor: 'F. E. Church, 1853', encuadre: '42% 50%' },
  pitahaya: { src: '/pinturas/pitahaya.webp', chica: '/pinturas/pitahaya-800.webp', titulo: 'Pitahayas, Colombia', autor: 'F. E. Church, 1853', encuadre: '35% 68%' },
  guacamaya: { src: '/pinturas/guacamaya.webp', chica: '/pinturas/guacamaya-800.webp', titulo: 'Guacamaya, Bogotá', autor: 'F. E. Church, 1853', encuadre: '55% 12%' },
  sudamerica: { src: '/pinturas/sudamerica.webp', chica: '/pinturas/sudamerica-800.webp', titulo: 'Paisaje sudamericano', autor: 'F. E. Church, década de 1850', encuadre: '45% 55%' },
  chimborazo: { src: '/pinturas/chimborazo.webp', chica: '/pinturas/chimborazo-800.webp', titulo: 'Chimborazo y Carihuairazo desde Riobamba', autor: 'F. E. Church, década de 1850', encuadre: '50% 35%' },
  sangay: { src: '/pinturas/sangay.webp', chica: '/pinturas/sangay-800.webp', titulo: 'El volcán Sangay en erupción', autor: 'F. E. Church, década de 1850', encuadre: '58% 40%' },
};

const CLAVES = Object.keys(PINTURAS);

/** Una pintura al azar (distinta de `evitar`, si se da): cada vez que se abre una sala cambia la obra. */
export function pinturaAlAzar(evitar) {
  const opciones = CLAVES.filter((c) => c !== evitar);
  return opciones[Math.floor(Math.random() * opciones.length)];
}
