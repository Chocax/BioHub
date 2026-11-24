import L from 'leaflet';

/** Ruta relativa al gateway (04 §8.1). La resuelve el cliente inyectado: nunca un host de OSM (SEC-J-04). */
export const RUTA_TESELAS = '/api/gateway/teselas/{z}/{x}/{y}.png';
/**
 * Desde este zoom las teselas se piden sin caché del navegador (SEC-J-24): a partir de 9 una
 * tesela cubre un área pequeña alrededor del hallazgo y, guardada en disco, delataría qué zonas
 * miró el usuario a quien use después el mismo equipo. Las de zoom bajo (país, departamento) sí se guardan.
 */
export const ZOOM_SIN_CACHE = 9;

/**
 * Capa de teselas que las pide al gateway con el JWT (11 §7, D56). Un `<img src>` no puede enviar
 * `Authorization`, así que cada tesela se descarga con `cliente.getBlob` y se muestra desde un object URL.
 *
 * Opciones propias: `cliente` ({ getBlob(ruta, { signal, sinCache }) }; con `sinCache: true` no debe usar la caché, D57) y `alSesionVencida` (se llama UNA vez ante un 401).
 *
 * Ciclo de vida de cada tesela (una entrada en `this._pendientes`):
 *  - un AbortController por tesela; se aborta cuando Leaflet la descarta (`tileunload`, `tileabort`,
 *    cambio de zoom o quitar la capa) para no gastar el límite de 600 teselas/min;
 *  - el object URL se revoca SIEMPRE: al cargar, al fallar al decodificar y al descartarse la tesela;
 *  - un aborto no llama a `done`, así que no dispara `tileerror` ni cuenta como fallo del mapa base;
 *  - cualquier otro fallo llama a `done(error)`: Leaflet dispara `tileerror` como con la capa normal.
 * Usa los internos `_tiles` y `_tileZoom` de Leaflet 1.9 (versión fijada en package.json).
 */
export const CapaTeselasGateway = L.TileLayer.extend({
  initialize(url, opciones) {
    L.TileLayer.prototype.initialize.call(this, url, opciones);
    this._pendientes = new Map(); // img -> { controlador, urlObjeto }
    this._sesionAvisada = false;
    this.on('tileunload tileabort', (e) => this._cancelar(e.tile));
  },

  createTile(coords, listo) {
    const img = document.createElement('img');
    img.alt = '';
    img.setAttribute('role', 'presentation');
    const entrada = { controlador: new AbortController(), urlObjeto: null };
    this._pendientes.set(img, entrada);

    const ruta = L.Util.template(this._url, { x: coords.x, y: coords.y, z: coords.z });
    // Promise.resolve().then: si el cliente no implementa getBlob el fallo llega como tileerror, no rompe a Leaflet.
    Promise.resolve()
      .then(() => this.options.cliente.getBlob(ruta, { signal: entrada.controlador.signal, sinCache: coords.z >= ZOOM_SIN_CACHE }))
      .then((blob) => {
        if (entrada.controlador.signal.aborted) return; // se descartó mientras bajaba
        entrada.urlObjeto = URL.createObjectURL(blob);
        img.onload = () => {
          this._liberar(img);
          listo(null, img);
        };
        img.onerror = () => {
          this._liberar(img);
          listo(new Error('tesela ilegible'), img);
        };
        img.src = entrada.urlObjeto;
      })
      .catch((error) => {
        if (error?.name === 'AbortError' || entrada.controlador.signal.aborted) return; // un aborto no es un error
        this._liberar(img);
        if (error?.status === 401 && !this._sesionAvisada) {
          this._sesionAvisada = true;
          this.options.alSesionVencida?.();
        }
        listo(error, img); // dispara tileerror (cuenta para onTeselasFallidas)
      });
    return img;
  },

  /** Cancela la descarga (si sigue en curso) y revoca el object URL. Es idempotente. */
  _cancelar(img) {
    const entrada = this._pendientes.get(img);
    if (!entrada) return;
    entrada.controlador.abort();
    this._liberar(img);
  },

  _liberar(img) {
    const entrada = this._pendientes.get(img);
    if (!entrada) return;
    if (entrada.urlObjeto) URL.revokeObjectURL(entrada.urlObjeto);
    this._pendientes.delete(img);
  },

  // Al cambiar de zoom Leaflet solo aborta las teselas con `img.complete === false`, y un <img> sin
  // `src` (aún descargando con fetch) cuenta como completo. Se abortan aquí las del zoom anterior.
  _abortLoading() {
    for (const clave of Object.keys(this._tiles)) {
      const { el, coords } = this._tiles[clave];
      const entrada = this._pendientes.get(el);
      if (coords.z !== this._tileZoom && entrada && !entrada.urlObjeto) {
        this._cancelar(el);
        L.DomUtil.remove(el);
        delete this._tiles[clave];
        this.fire('tileabort', { tile: el, coords });
      }
    }
    L.TileLayer.prototype._abortLoading.call(this);
  },
});
