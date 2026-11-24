/*
 * Fondo del inicio de sesión de BioHub: diecinueve pinturas de dominio público que se alternan cada 6,5 s con un fundido
 * de 1,8 s. Cada imagen se carga justo antes de mostrarse (la siguiente y la de después), así que no se bajan todas
 * de entrada. Con movimiento reducido se queda la primera.
 * El orden se baraja en cada visita y la primera nunca es la misma con la que arrancó la vez anterior (solo se
 * recuerda su nombre en localStorage; si el navegador no deja guardar, igual se baraja).
 */
(function () {
  // Las mismas 19 del carrusel interno y del portal (FONDOS_GRANDES en web-interna/src/componentes/pinturas.js)
  var FONDOS = ['andes', 'rio', 'colibri', 'tequendama', 'lluvia', 'pasiflora', 'nubes', 'tropico', 'tequendama-cima',
    'pitahaya', 'guacamaya', 'sudamerica', 'chimborazo', 'sangay', 'chita', 'cachiri', 'mariquita', 'bongo', 'choachi'];
  var CADA_MS = 6500;
  var CLAVE = 'biohub.login.primera';
  var base = (document.currentScript && document.currentScript.src.replace(/js\/fondos\.js.*$/, 'img/')) || '';

  function barajar(lista) {
    var orden = lista.slice();
    for (var i = orden.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = orden[i]; orden[i] = orden[j]; orden[j] = t;
    }
    var anterior = null;
    try { anterior = window.localStorage.getItem(CLAVE); } catch (e) { /* sin almacenamiento */ }
    if (orden.length > 1 && orden[0] === anterior) {
      var k = 1 + Math.floor(Math.random() * (orden.length - 1));
      var u = orden[0]; orden[0] = orden[k]; orden[k] = u;
    }
    try { window.localStorage.setItem(CLAVE, orden[0]); } catch (e) { /* sin almacenamiento */ }
    return orden;
  }
  FONDOS = barajar(FONDOS);

  function iniciar() {
    var capas = FONDOS.map(function (nombre, i) {
      var capa = document.createElement('div');
      capa.className = 'bh-fondo' + (i === 0 ? ' bh-fondo--activo' : '');
      capa.setAttribute('aria-hidden', 'true');
      capa.dataset.imagen = base + nombre + '.webp';
      if (i === 0) capa.style.backgroundImage = 'url("' + capa.dataset.imagen + '")';
      document.body.insertBefore(capa, document.body.firstChild);
      return capa;
    });
    document.documentElement.classList.add('bh-con-fondos');
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    var actual = 0;
    function precargar(i) {
      var capa = capas[i];
      if (!capa.style.backgroundImage) capa.style.backgroundImage = 'url("' + capa.dataset.imagen + '")';
    }
    precargar(1);
    window.setInterval(function () {
      var siguiente = (actual + 1) % capas.length;
      precargar(siguiente);
      capas[actual].classList.remove('bh-fondo--activo');
      capas[siguiente].classList.add('bh-fondo--activo');
      actual = siguiente;
      precargar((siguiente + 1) % capas.length);
    }, CADA_MS);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar);
  else iniciar();
})();
