/*
 * Fondo del inicio de sesión de BioHub: doce pinturas de dominio público que se alternan cada 6,5 s con un fundido
 * de 1,8 s. Cada imagen se carga justo antes de mostrarse (la siguiente y la de después), así que no se bajan todas
 * de entrada. Con movimiento reducido se queda la primera.
 */
(function () {
  var FONDOS = ['andes', 'tequendama-cima', 'colibri', 'chimborazo', 'rio', 'lluvia', 'sudamerica', 'pasiflora',
    'tequendama', 'sangay', 'tropico', 'nubes'];
  var CADA_MS = 6500;
  var base = (document.currentScript && document.currentScript.src.replace(/js\/fondos\.js.*$/, 'img/')) || '';

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
