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
  nubes: { src: '/pinturas/nubes.webp', chica: '/pinturas/nubes-800.webp', titulo: 'Estudio de cúmulos', autor: 'Círculo de J. Constable, siglo XIX' },
  tropico: { src: '/pinturas/tropico.webp', chica: '/pinturas/tropico-800.webp', titulo: 'Mañana en el trópico', autor: 'F. E. Church, 1858' },
  // Historia de Colombia: Comisión Corográfica (1850-1852), E. W. Mark (1843-1846), la Expedición Botánica y Humboldt
  chita: { src: '/pinturas/chita.webp', chica: '/pinturas/chita-800.webp', titulo: 'Nevado de Chita, hacia Güicán', autor: 'C. Fernández, Comisión Corográfica, 1851', encuadre: '50% 40%' },
  cachiri: { src: '/pinturas/cachiri.webp', chica: '/pinturas/cachiri-800.webp', titulo: 'Casa principal de Cachirí', autor: 'C. Fernández, Comisión Corográfica, 1850', encuadre: '50% 60%' },
  mariquita: { src: '/pinturas/mariquita.webp', chica: '/pinturas/mariquita-800.webp', titulo: 'Minas de plata de Santa Ana, Mariquita', autor: 'H. Price, Comisión Corográfica, 1852', encuadre: '45% 60%' },
  bongo: { src: '/pinturas/bongo.webp', chica: '/pinturas/bongo-800.webp', titulo: 'Bongo del Magdalena', autor: 'E. W. Mark, 1845', encuadre: '40% 65%' },
  choachi: { src: '/pinturas/choachi.webp', chica: '/pinturas/choachi-800.webp', titulo: 'Rancho campesino', autor: 'E. W. Mark, 1846', encuadre: '45% 45%' },
  'santa-marta': { src: '/pinturas/santa-marta.webp', chica: '/pinturas/santa-marta-800.webp', titulo: 'Catedral de Santa Marta', autor: 'E. W. Mark, 1844', encuadre: '50% 60%' },
  mutisia: { src: '/pinturas/mutisia.webp', chica: '/pinturas/mutisia-800.webp', titulo: 'Mutisia clematis', autor: 'S. Rizo, Expedición Botánica', encuadre: '50% 35%' },
  icononzo: { src: '/pinturas/icononzo.webp', chica: '/pinturas/icononzo-800.webp', titulo: 'Puentes naturales de Icononzo', autor: 'A. von Humboldt, 1810', encuadre: '50% 30%' },
  // El cóndor andino, ave del escudo de Colombia: lámina del viaje de Humboldt y Bonpland (Internet Archive, McGill)
  condor: { src: '/pinturas/condor.webp', chica: '/pinturas/condor-800.webp', titulo: 'Cabeza del cóndor (Vultur gryphus)', autor: 'A. von Humboldt y A. Bonpland, 1811', encuadre: '45% 40%' },
  sangay: { src: '/pinturas/sangay.webp', chica: '/pinturas/sangay-800.webp', titulo: 'El volcán Sangay en erupción', autor: 'F. E. Church, década de 1850', encuadre: '58% 40%' },
};

const CLAVES = Object.keys(PINTURAS);

/**
 * Las obras que se ven a pantalla completa: el carrusel del inicio interno, el cielo del portal y el fondo del login
 * usan exactamente estas 20 (en orden aleatorio). Quedan fuera las tres verticales y pequeñas (Mutisia, Icononzo y la
 * catedral de Santa Marta), que solo aparecen en las salas. La prueba listasDeFondos.test.js vigila que las tres listas
 * (aquí, web-publica/src/paginas/Inicio.jsx y el fondos.js del tema de Keycloak) sigan iguales.
 */
export const FONDOS_GRANDES = ['andes', 'rio', 'colibri', 'tequendama', 'lluvia', 'pasiflora', 'nubes', 'tropico',
  'tequendama-cima', 'pitahaya', 'guacamaya', 'sudamerica', 'chimborazo', 'sangay', 'chita', 'cachiri', 'mariquita',
  'bongo', 'choachi', 'condor'];

/** Una pintura al azar (distinta de `evitar`, si se da): cada vez que se abre una sala cambia la obra. */
export function pinturaAlAzar(evitar) {
  const opciones = CLAVES.filter((c) => c !== evitar);
  return opciones[Math.floor(Math.random() * opciones.length)];
}
