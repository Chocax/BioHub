/**
 * Baraja una lista (Fisher-Yates) para que las pinturas no salgan siempre en el mismo orden. Además, la primera nunca
 * es la misma con la que arrancó la visita anterior: solo se recuerda cuál fue (su `id`) en localStorage. Si el
 * navegador no deja guardar (modo privado, almacenamiento bloqueado), igual baraja.
 *
 * @param lista  elementos a barajar (no se modifica)
 * @param clave  nombre con que se recuerda la primera de la última vez
 * @param id     función que da un identificador estable de cada elemento
 * @param azar   generador entre 0 y 1 (se cambia en las pruebas)
 */
export function ordenAleatorio(lista, clave, id, azar = Math.random) {
  const orden = [...lista];
  for (let i = orden.length - 1; i > 0; i -= 1) {
    const j = Math.floor(azar() * (i + 1));
    [orden[i], orden[j]] = [orden[j], orden[i]];
  }
  let anterior = null;
  try {
    anterior = window.localStorage.getItem(clave);
  } catch {
    // sin almacenamiento: solo se baraja
  }
  if (orden.length > 1 && anterior !== null && id(orden[0]) === anterior) {
    const j = 1 + Math.floor(azar() * (orden.length - 1));
    [orden[0], orden[j]] = [orden[j], orden[0]];
  }
  try {
    window.localStorage.setItem(clave, id(orden[0]));
  } catch {
    // sin almacenamiento
  }
  return orden;
}
