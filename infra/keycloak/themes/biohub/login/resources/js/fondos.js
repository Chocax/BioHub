/*
 * Fondo del inicio de sesión de BioHub: veinte pinturas de dominio público que se alternan cada 6,5 s con un fundido
 * de 1,8 s. Cada imagen se carga justo antes de mostrarse (la siguiente y la de después), así que no se bajan todas
 * de entrada. Con movimiento reducido se queda la primera.
 * El orden se baraja en cada visita y la primera nunca es la misma con la que arrancó la vez anterior (solo se
 * recuerda su nombre en localStorage; si el navegador no deja guardar, igual se baraja).
 */
(function () {
  // Las mismas 20 del carrusel interno y del portal (FONDOS_GRANDES en web-interna/src/componentes/pinturas.js)
  var FONDOS = ['andes', 'rio', 'colibri', 'tequendama', 'lluvia', 'pasiflora', 'nubes', 'tropico', 'tequendama-cima',
    'pitahaya', 'guacamaya', 'sudamerica', 'chimborazo', 'sangay', 'chita', 'cachiri', 'mariquita', 'bongo', 'choachi', 'condor'];
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

    // Dos cóndores andinos planeando en círculos (la térmica): uno cerca, a la izquierda, y otro lejos, más pequeño.
    [
      { izq: '15vw', arriba: '32vh', ancho: '200px', vuelta: '44s', ruta: 'path("M 0 -70 A 210 70 0 1 1 0 70 A 210 70 0 1 1 0 -70")', clase: '' },
      { izq: '80vw', arriba: '20vh', ancho: '96px', vuelta: '58s', ruta: 'path("M 0 50 A 140 50 0 1 0 0 -50 A 140 50 0 1 0 0 50")', clase: ' bh-condor--lejos' },
    ].forEach(function (c) {
      var vuelo = document.createElement('div');
      vuelo.className = 'bh-condor' + c.clase;
      vuelo.setAttribute('aria-hidden', 'true');
      vuelo.style.left = c.izq; vuelo.style.top = c.arriba;
      vuelo.style.setProperty('--bh-condor-ancho', c.ancho);
      vuelo.style.setProperty('--bh-condor-vuelta', c.vuelta);
      vuelo.style.setProperty('--bh-condor-ruta', c.ruta);
      var ave = document.createElement('img');
      ave.src = base + 'condor.svg'; ave.alt = '';
      vuelo.appendChild(ave);
      document.body.insertBefore(vuelo, capas[0].nextSibling); // justo después de las pinturas
    });

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
