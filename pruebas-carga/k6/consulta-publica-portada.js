// ASR1 / RNF1: pico >= 60x sobre la PORTADA de web-publica (D65/D66). Una iteración = una visita a la portada:
//   1) en paralelo: GET /api/publica/especies?page=0&size=12 y GET /api/publica/especies/familias;
//   2) con los `fotoId` no nulos de la página (hasta 12): GET /api/publica/especies/fotos/{fotoId}, en paralelo y de a 6
//      por vez (el límite por host de un navegador).
// Son 14 peticiones por visita (ARQ-R-11). El perfil es el de consulta-publica-pico.js (arrival-rate: la tasa de VISITAS no
// depende de la latencia): base N visitas/s (T_BASE) -> rampa a MULT*N (T_RAMP) -> pico (T_PICO) -> baja a N (T_BAJA) -> N (T_POST).
//
// Caché fría / caliente: este script no la controla; lo hace quien lo corre (README, "Portada: caché fría y caliente"):
// fría = Redis vaciado (datos/vaciar-cache.sh) y réplicas recién reiniciadas, sin calentamiento; caliente = tras una corrida
// corta previa. Los bytes de las fotos NO se cachean en el servidor (FotosEspeciesService): cada foto es una lectura de
// foto_publica en MySQL, fría o caliente (la caché de Redis solo cubre el listado y las familias).
//
// Visitante que repite: REPETIDA=<0..100> es el porcentaje de visitas cuyo navegador ya tiene las fotos (envía If-None-Match
// con el ETag que recibió antes en ese mismo VU y espera 304). Por defecto 0: todas las visitas son "primera visita" (200 con
// 14 peticiones y ~12 fotos completas), el peor caso. Un 304 cuenta como éxito solo en esas peticiones condicionales.
//
// Ejecutar (desde pruebas-carga/):  ./correr-k6.sh k6/consulta-publica-portada.js -e N=2 -e SALIDA=/resultados/asr1-portada-2r-n2.json
// Requiere las fichas y las fotos sembradas (./datos/sembrar.sh y ./datos/sembrar-fotos.sh).
// Variables: BASE_URL, N (visitas/s, def. 2), MULT (def. 60), T_BASE (60s), T_RAMP (30s), T_PICO (120s), T_BAJA (10s),
//            T_POST (180s), MAX_VUS (def. 4000), REPETIDA (def. 0), ENTORNO y CACHE (texto libre para `parametros`), SALIDA.
// Metadatos D42 en `parametros`: REPLICAS, CPUS, COMMIT y RED_K6 (correr-k6.sh pasa COMMIT y RED_K6; REPLICAS y CPUS se exportan).
import http from 'k6/http';
import exec from 'k6/execution';
import { Rate, Counter, Trend } from 'k6/metrics';
import { BASE_URL, errores5xx, ventana, aSegundos, VENTANA_S } from './lib/comun.js';

const N = parseInt(__ENV.N || '2');
const MULT = parseInt(__ENV.MULT || '60');
const REPETIDA = Math.max(0, Math.min(100, parseInt(__ENV.REPETIDA || '0')));
const T = {
  base: aSegundos(__ENV.T_BASE || '60s'), rampa: aSegundos(__ENV.T_RAMP || '30s'),
  pico: aSegundos(__ENV.T_PICO || '120s'), baja: aSegundos(__ENV.T_BAJA || '10s'), post: aSegundos(__ENV.T_POST || '180s'),
};
const TOTAL = T.base + T.rampa + T.pico + T.baja + T.post;
const FIN_RAMPA = T.base + T.rampa;
const FIN_PICO = FIN_RAMPA + T.pico;
const NVENT = Math.ceil(TOTAL / VENTANA_S);

// Un navegador abre a lo sumo 6 conexiones por host: las fotos se piden de a 6.
const POR_HOST = 6;

const visitaOk = new Rate('visita_ok');                 // visita completa: todas sus peticiones con el estado esperado
const visitaMs = new Trend('visita_ms', true);          // desde la primera petición hasta la última foto
const visitas = new Counter('visitas');
const fotosPedidas = new Counter('fotos_pedidas');
const fotosSinId = new Counter('visitas_sin_fotoid');   // la página llegó sin ningún fotoId (degradada, o semilla faltante)
const degradadas = new Counter('visitas_degradadas');   // el listado trajo `modoDegradado: true` (ARQ-R-5: sin fotos ni familias)
const etags = {};                                        // por VU: fotoId -> ETag (visitante que repite)

http.setResponseCallback(http.expectedStatuses(200));

const umbralesVentana = {};
for (let i = 0; i < NVENT; i++) {
  umbralesVentana[`http_req_duration{ventana:w${String(i).padStart(2, '0')}}`] = ['p(95)<2000'];
}

export const options = {
  scenarios: {
    pico: {
      executor: 'ramping-arrival-rate',
      startRate: N, timeUnit: '1s',
      preAllocatedVUs: Math.min(500, N * 20), maxVUs: parseInt(__ENV.MAX_VUS || '4000'),
      stages: [
        { target: N, duration: `${T.base}s` },
        { target: N * MULT, duration: `${T.rampa}s` },
        { target: N * MULT, duration: `${T.pico}s` },
        { target: N, duration: `${T.baja}s` },
        { target: N, duration: `${T.post}s` },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    errores_5xx: ['rate<0.05'],
    visita_ok: ['rate>0.95'],
    http_req_duration: ['p(95)<2000'],
    'http_req_duration{fase:base}': ['p(95)<2000'],
    'http_req_duration{fase:pico}': ['p(95)<2000'],
    'http_req_duration{fase:post}': ['p(95)<2000'],
    'http_req_duration{tipo:lista}': ['p(95)<2000'],
    'http_req_duration{tipo:familias}': ['p(95)<2000'],
    'http_req_duration{tipo:foto}': ['p(95)<2000'],
    ...umbralesVentana,
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

function fase(seg) {
  if (seg < T.base) return 'base';
  if (seg < FIN_RAMPA) return 'rampa';
  if (seg < FIN_PICO) return 'pico';
  if (seg < FIN_PICO + T.baja) return 'baja';
  return 'post';
}

function estadoOk(res, esperado) {
  errores5xx.add(res.status === 0 || res.status >= 500);
  return res.status === esperado;
}

export default function () {
  const ms = Date.now() - exec.scenario.startTime;
  const et = { ventana: ventana(ms), fase: fase(ms / 1000) };
  const t0 = Date.now();
  let ok = true;

  // 1) listado y familias en paralelo (el listado se lee como texto para sacar los fotoId)
  const [lista, familias] = http.batch([
    ['GET', `${BASE_URL}/api/publica/especies?page=0&size=12`, null,
      { tags: { ...et, tipo: 'lista', name: 'GET /api/publica/especies' }, timeout: '10s', responseType: 'text' }],
    ['GET', `${BASE_URL}/api/publica/especies/familias`, null,
      { tags: { ...et, tipo: 'familias', name: 'GET /api/publica/especies/familias' }, timeout: '10s' }],
  ]);
  ok = estadoOk(lista, 200) && ok;
  ok = estadoOk(familias, 200) && ok;

  // 2) fotos: hasta 12 fotoId no nulos, de a POR_HOST
  let ids = [];
  if (lista.status === 200) {
    try {
      const cuerpo = JSON.parse(lista.body);
      if (cuerpo.modoDegradado === true) degradadas.add(1);
      ids = cuerpo.content.map((e) => e.fotoId).filter((x) => x).slice(0, 12);
    } catch (e) {
      ok = false;
    }
  }
  if (lista.status === 200 && ids.length === 0) fotosSinId.add(1);
  for (let i = 0; i < ids.length; i += POR_HOST) {
    const lote = ids.slice(i, i + POR_HOST).map((id) => {
      const condicional = REPETIDA > 0 && etags[id] && Math.random() * 100 < REPETIDA;
      const params = { tags: { ...et, tipo: 'foto', name: 'GET /api/publica/especies/fotos/{fotoId}' }, timeout: '10s',
        responseType: 'none' };
      if (condicional) {
        params.headers = { 'If-None-Match': etags[id] };
        params.responseCallback = http.expectedStatuses(304);
      }
      return { id, condicional, peticion: ['GET', `${BASE_URL}/api/publica/especies/fotos/${id}`, null, params] };
    });
    const resps = http.batch(lote.map((p) => p.peticion));
    resps.forEach((r, k) => {
      ok = estadoOk(r, lote[k].condicional ? 304 : 200) && ok;
      fotosPedidas.add(1);
      if (r.status === 200 && r.headers.Etag) etags[lote[k].id] = r.headers.Etag;
    });
  }
  visitas.add(1);
  visitaOk.add(ok);
  visitaMs.add(Date.now() - t0, { fase: et.fase });
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (nombre, stat) => (m[nombre] && m[nombre].values && m[nombre].values[stat] !== undefined ? m[nombre].values[stat] : null);
  const por_fase = {};
  for (const f of ['base', 'pico', 'post']) {
    const k = `http_req_duration{fase:${f}}`;
    por_fase[f] = { p50: v(k, 'med'), p95: v(k, 'p(95)'), p99: v(k, 'p(99)') };
  }
  const por_tipo = {};
  for (const t of ['lista', 'familias', 'foto']) {
    const k = `http_req_duration{tipo:${t}}`;
    por_tipo[t] = { p50: v(k, 'med'), p95: v(k, 'p(95)'), p99: v(k, 'p(99)'), max: v(k, 'max') };
  }
  const ventanas = [];
  for (let i = 0; i < NVENT; i++) {
    const w = 'w' + String(i).padStart(2, '0');
    const k = `http_req_duration{ventana:${w}}`;
    ventanas.push({ ventana: w, desde_s: i * VENTANA_S, p95: v(k, 'p(95)'), p99: v(k, 'p(99)') });
  }
  const tras = ventanas.filter((x) => x.desde_s + VENTANA_S > FIN_RAMPA && x.p95 !== null);
  let ultimaMala = -1;
  tras.forEach((x, idx) => { if (x.p95 >= 2000) ultimaMala = idx; });
  let estabilizacion_s = 0;
  if (ultimaMala >= 0) {
    estabilizacion_s = ultimaMala + 1 < tras.length ? Math.max(0, tras[ultimaMala + 1].desde_s - FIN_RAMPA) : null;
  }
  const peticiones = v('http_reqs', 'count');
  const descartadas = v('dropped_iterations', 'count') || 0;
  const nDegradadas = v('visitas_degradadas', 'count') || 0;
  const nVisitas = v('visitas', 'count') || 0;
  const porVisita = nVisitas ? peticiones / nVisitas : 14;
  const tasaOk = v('visita_ok', 'rate');
  const resumen = {
    // Metadatos exigidos por D42 (réplicas, CPU por réplica, commit y red de k6). correr-k6.sh rellena COMMIT y RED_K6.
    parametros: { escenario: 'portada', N_visitas_s: N, MULT, pico_visitas_s: N * MULT, peticiones_por_visita_aprox: Math.round(porVisita * 10) / 10,
      pico_peticiones_s_aprox: Math.round(N * MULT * porVisita), repetida_pct: REPETIDA, T, total_s: TOTAL, fin_rampa_s: FIN_RAMPA,
      fin_pico_s: FIN_PICO, replicas: __ENV.REPLICAS || null, cpus_por_replica: __ENV.CPUS || null, commit: __ENV.COMMIT || null,
      red_k6: __ENV.RED_K6 || null, fecha: new Date().toISOString(), entorno: __ENV.ENTORNO || null, cache: __ENV.CACHE || null },
    visitas: nVisitas,
    // Con `modoDegradado: true` el catálogo va sin fotoId y las familias vacías (ARQ-R-5): la visita cuenta como éxito si todo fue 200
    // (es la táctica de ASR1), pero se informa aparte. `visitas_sin_fotoid` incluye las degradadas.
    visitas_degradadas: nDegradadas,
    visitas_degradadas_pct: nVisitas ? nDegradadas / nVisitas * 100 : null,
    visitas_sin_fotoid: v('visitas_sin_fotoid', 'count') || 0,
    peticiones,
    fotos_pedidas: v('fotos_pedidas', 'count') || 0,
    tasa_media_req_s: v('http_reqs', 'rate'),
    iteraciones_descartadas: descartadas,
    vus_max: v('vus_max', 'max'),
    // D42: éxito = completas / (iniciadas + descartadas). Una visita cuenta como éxito solo si TODAS sus peticiones
    // tuvieron el estado esperado; la carga que k6 no pudo emitir (iteraciones descartadas) cuenta como fallo.
    exito_d42_pct: tasaOk === null || !(nVisitas + descartadas) ? null : tasaOk * nVisitas / (nVisitas + descartadas) * 100,
    // Lo mismo por petición (las descartadas se convierten en peticiones con el promedio observado por visita).
    exito_d42_peticiones_pct: v('http_req_failed', 'rate') === null || !(peticiones + descartadas * porVisita) ? null
      : (1 - v('http_req_failed', 'rate')) * peticiones / (peticiones + descartadas * porVisita) * 100,
    fallos_no_esperados_pct: v('http_req_failed', 'rate') === null ? null : v('http_req_failed', 'rate') * 100,
    errores_5xx_pct: v('errores_5xx', 'rate') === null ? null : v('errores_5xx', 'rate') * 100,
    latencia_ms: { p50: v('http_req_duration', 'med'), p90: v('http_req_duration', 'p(90)'), p95: v('http_req_duration', 'p(95)'), p99: v('http_req_duration', 'p(99)'), max: v('http_req_duration', 'max') },
    visita_ms: { p50: v('visita_ms', 'med'), p95: v('visita_ms', 'p(95)'), p99: v('visita_ms', 'p(99)'), max: v('visita_ms', 'max') },
    por_tipo,
    por_fase,
    bytes_recibidos: v('data_received', 'count'),
    bytes_recibidos_mb_s: v('data_received', 'rate') === null ? null : v('data_received', 'rate') / 1e6,
    estabilizacion_s,
    ventanas,
  };
  const salida = __ENV.SALIDA || '/resultados/portada.json';
  return { [salida]: JSON.stringify(resumen, null, 2), stdout: textoResumen(resumen) };
}

function textoResumen(r) {
  const f = (x) => (x === null || x === undefined ? 'n/d' : x.toFixed(1));
  let s = `\n=== ASR1/RNF1 portada x${MULT} (N=${N} visitas/s, pico=${N * MULT} visitas/s, ~${r.parametros.pico_peticiones_s_aprox} req/s) ===\n`;
  s += `visitas=${r.visitas} peticiones=${r.peticiones} fotos=${r.fotos_pedidas} descartadas=${r.iteraciones_descartadas} vus_max=${r.vus_max} sin_fotoid=${r.visitas_sin_fotoid} degradadas=${r.visitas_degradadas} (${f(r.visitas_degradadas_pct)}%)\n`;
  s += `exito_d42=${f(r.exito_d42_pct)}%  no-esperados=${f(r.fallos_no_esperados_pct)}%  5xx/timeouts=${f(r.errores_5xx_pct)}%  recibido=${f(r.bytes_recibidos_mb_s)} MB/s\n`;
  s += `p50=${f(r.latencia_ms.p50)} p95=${f(r.latencia_ms.p95)} p99=${f(r.latencia_ms.p99)} max=${f(r.latencia_ms.max)} ms; visita p95=${f(r.visita_ms.p95)} ms\n`;
  for (const k of Object.keys(r.por_tipo)) s += `  ${k}: p50=${f(r.por_tipo[k].p50)} p95=${f(r.por_tipo[k].p95)} p99=${f(r.por_tipo[k].p99)}\n`;
  for (const k of Object.keys(r.por_fase)) s += `  fase ${k}: p50=${f(r.por_fase[k].p50)} p95=${f(r.por_fase[k].p95)} p99=${f(r.por_fase[k].p99)}\n`;
  s += `estabilizacion=${r.estabilizacion_s === null ? 'NUNCA' : r.estabilizacion_s + ' s'} desde el fin de la rampa\n`;
  s += r.ventanas.map((x) => `${x.ventana}(${x.desde_s}s)=${f(x.p95)}`).join(' ') + '\n';
  return s;
}
