/**
 * Baraja una lista (Fisher-Yates) para que las pinturas no salgan siempre en el mismo orden.
 *
 * El portal NO guarda nada en el navegador (WP-08: ni localStorage, ni sessionStorage, ni cookies), así que no
 * recuerda con qué pintura arrancó la visita anterior: puede repetir la primera, con probabilidad 1 entre el
 * número de obras. La web interna y el login sí la recuerdan.
 *
 * @param lista  elementos a barajar (no se modifica)
 * @param azar   generador entre 0 y 1 (se cambia en las pruebas)
 */
export function ordenAleatorio(lista, azar = Math.random) {
  const orden = [...lista];
  for (let i = orden.length - 1; i > 0; i -= 1) {
    const j = Math.floor(azar() * (i + 1));
    [orden[i], orden[j]] = [orden[j], orden[i]];
  }
  return orden;
}
