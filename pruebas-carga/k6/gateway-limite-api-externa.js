// ASR8 / RNF11 / HU-08 c.2 / HU-12 c.2: el gateway limita a un consumidor externo abusivo SIN afectar al resto.
//
// Qué demuestra (todo contra el api-gateway real, con Redis, MySQL y Keycloak del entorno local o desechable; D30):
//   1. Consumidor A (límite por defecto, 60/min) manda ABUSO_RPS peticiones por segundo durante T_ABUSO:
//      recibe como máximo 60 respuestas 200 por ventana de un minuto y el resto 429 con Retry-After. Ningún 5xx.
//   2. Consumidor B (límite configurado por el administrador: LIMITE_B/min, RNF11 "configurable") y consumidor C
//      (límite por defecto) siguen recibiendo 200 en el 100 % de sus peticiones mientras A abusa.
//   3. El portal público (sin key, 120/min por IP) sigue en 200 durante el abuso.
//   4. La latencia de los cumplidores durante el abuso no sube de forma apreciable frente a la línea base.
//   5. Consumidor D: el administrador revoca su key a mitad de la prueba y TODA petición posterior recibe 401
//      (HU-12 c.2, "de inmediato").
//
// Perfil: base T_BASE (B, C, portal y D) -> abuso T_ABUSO (entra A) -> post T_POST (A se detiene).
//
// Ejecutar (desde pruebas-carga/, con el gateway arriba):
//   export TOKEN=<JWT de admin1>   # nunca -e TOKEN=...: se vería con ps (SEC-J-14). Vive 5 min: sácalo justo antes.
//   ./correr-k6.sh k6/gateway-limite-api-externa.js -e BASE_URL=http://127.0.0.1:8080 -e SALIDA=/resultados/asr8-gateway.json
// Variables: BASE_URL (gateway), ABUSO_RPS (def. 20), LIMITE_B (def. 120), LIMITE_POR_DEFECTO (def. 60, el del gateway),
//            T_BASE (60s), T_ABUSO (120s), T_POST (30s), SALIDA. Crea 4 consumidores con prefijo "k6-asr8-" y al final
//            revoca sus keys (el gateway no permite borrar consumidores: quedan como registro).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const ABUSO_RPS = parseInt(__ENV.ABUSO_RPS || '20');
const LIMITE_B = parseInt(__ENV.LIMITE_B || '120');
const LIMITE_POR_DEFECTO = parseInt(__ENV.LIMITE_POR_DEFECTO || '60');
const seg = (v) => parseInt(String(v).replace(/s$/, ''));
const T = { base: seg(__ENV.T_BASE || '60s'), abuso: seg(__ENV.T_ABUSO || '120s'), post: seg(__ENV.T_POST || '30s') };
const TOTAL = T.base + T.abuso + T.post;
// Ventana fija de 60 s (D46): durante T_ABUSO, A puede tocar ceil(T_ABUSO/60) + 1 ventanas distintas.
const MAX_200_ABUSIVO = LIMITE_POR_DEFECTO * (Math.ceil(T.abuso / 60) + 1);
const FICHAS = '/api/v1/externa/fichas';

// Métricas propias: cada una responde una pregunta del ASR.
const okB = new Rate('cumplidor_b_200');           // B con su límite configurado: debe ser 100 %
const okC = new Rate('cumplidor_c_200');           // C con el límite por defecto: debe ser 100 %
const okPortal = new Rate('portal_200');           // portal sin key: debe ser 100 %
const a200 = new Counter('abusivo_200');           // como máximo MAX_200_ABUSIVO
const a429 = new Counter('abusivo_429');           // tiene que haber
const a429SinRetry = new Counter('abusivo_429_sin_retry_after');
const aOtros = new Counter('abusivo_otro_estado'); // 5xx, 401, timeouts...: ninguno
const dAntes = new Rate('revocada_200_antes');     // D antes de revocar: 100 % 200
const dDespues = new Rate('revocada_401_despues'); // D después de revocar: 100 % 401
const dAceptada = new Counter('revocada_aceptada_tras_revocar');

export const options = {
  scenarios: {
    cumplidor_b: { executor: 'constant-arrival-rate', exec: 'cumplidorB', rate: 1, timeUnit: '1s',
      duration: `${TOTAL}s`, preAllocatedVUs: 5, maxVUs: 50 },
    cumplidor_c: { executor: 'constant-arrival-rate', exec: 'cumplidorC', rate: 30, timeUnit: '1m',
      duration: `${TOTAL}s`, preAllocatedVUs: 5, maxVUs: 50 },
    portal: { executor: 'constant-arrival-rate', exec: 'portal', rate: 1, timeUnit: '1s',
      duration: `${TOTAL}s`, preAllocatedVUs: 5, maxVUs: 50 },
    abusivo: { executor: 'constant-arrival-rate', exec: 'abusivo', rate: ABUSO_RPS, timeUnit: '1s',
      startTime: `${T.base}s`, duration: `${T.abuso}s`, preAllocatedVUs: Math.max(20, ABUSO_RPS * 2),
      maxVUs: Math.max(200, ABUSO_RPS * 10) },
    revocacion: { executor: 'per-vu-iterations', exec: 'revocacion', vus: 1, iterations: 1,
      startTime: `${T.base}s`, maxDuration: `${T.abuso + T.post}s` },
  },
  thresholds: {
    cumplidor_b_200: ['rate==1'],
    cumplidor_c_200: ['rate==1'],
    portal_200: ['rate==1'],
    abusivo_200: [`count<=${MAX_200_ABUSIVO}`],
    abusivo_429: ['count>0'],
    abusivo_429_sin_retry_after: ['count==0'],
    abusivo_otro_estado: ['count==0'],
    revocada_200_antes: ['rate==1'],
    revocada_401_despues: ['rate==1'],
    revocada_aceptada_tras_revocar: ['count==0'],
    // Sub-métricas por fase: k6 solo las calcula si un umbral las nombra. El límite real es la comparación
    // base vs abuso que se reporta en el resumen; 1 s es solo un techo de cordura.
    'http_req_duration{grupo:cumplidor,fase:base}': ['p(95)<1000'],
    'http_req_duration{grupo:cumplidor,fase:abuso}': ['p(95)<1000'],
    'http_req_duration{grupo:cumplidor,fase:post}': ['p(95)<1000'],
    'http_req_duration{grupo:abusivo}': ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

// 401 y 429 son respuestas esperadas en esta prueba: no deben contar como fallo de red en http_req_failed.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 401, 429));

function admin(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, responseType: 'text' };
}

function crearConsumidor(token, sufijo, limite) {
  const nombre = `k6-asr8-${Date.now()}-${sufijo}`;
  const cuerpo = { nombre, contactoEmail: `${sufijo.toLowerCase()}@ejemplo.org` };
  if (limite) cuerpo.limitePorMinuto = limite;
  const c = http.post(`${BASE}/api/gateway/consumidores`, JSON.stringify(cuerpo), admin(token));
  if (c.status !== 201) throw new Error(`crear consumidor ${sufijo}: HTTP ${c.status}`);
  const k = http.post(`${BASE}/api/gateway/consumidores/${c.json('id')}/keys`, null, admin(token));
  if (k.status !== 201) throw new Error(`emitir key ${sufijo}: HTTP ${k.status}`);
  return { consumidor: c.json('id'), keyId: k.json('id'), key: k.json('apiKey'), prefijo: k.json('prefijo') };
}

export function setup() {
  const token = __ENV.TOKEN;
  if (!token) throw new Error('Falta TOKEN (JWT de admin1): export TOKEN=... antes de correr');
  // Las métricas que nunca reciben muestras no aparecen en el resumen: se inicializan en 0.
  [a200, a429, a429SinRetry, aOtros, dAceptada].forEach((m) => m.add(0));
  const consumidores = {
    a: crearConsumidor(token, 'A', null),
    b: crearConsumidor(token, 'B', LIMITE_B),
    c: crearConsumidor(token, 'C', null),
    d: crearConsumidor(token, 'D', null),
  };
  return { token, consumidores, inicio: Date.now() };
}

function fase(data) {
  const s = (Date.now() - data.inicio) / 1000;
  if (s < T.base) return 'base';
  if (s < T.base + T.abuso) return 'abuso';
  return 'post';
}

function pedirConKey(key, etiquetas) {
  return http.get(`${BASE}${FICHAS}?size=5`, { headers: { 'X-API-Key': key }, tags: etiquetas, timeout: '10s' });
}

export function cumplidorB(data) {
  const r = pedirConKey(data.consumidores.b.key, { grupo: 'cumplidor', quien: 'B', fase: fase(data) });
  okB.add(r.status === 200);
}

export function cumplidorC(data) {
  const r = pedirConKey(data.consumidores.c.key, { grupo: 'cumplidor', quien: 'C', fase: fase(data) });
  okC.add(r.status === 200);
}

export function portal(data) {
  const r = http.get(`${BASE}/api/publica/fichas?size=5`, { tags: { grupo: 'cumplidor', quien: 'portal', fase: fase(data) }, timeout: '10s' });
  okPortal.add(r.status === 200);
}

export function abusivo(data) {
  const r = pedirConKey(data.consumidores.a.key, { grupo: 'abusivo', quien: 'A', fase: fase(data) });
  if (r.status === 200) a200.add(1);
  else if (r.status === 429) {
    a429.add(1);
    if (!r.headers['Retry-After']) a429SinRetry.add(1);
  } else aOtros.add(1);
}

// Un solo VU: D pide a un ritmo normal, el administrador revoca su key y D sigue intentando.
// Una petición cada 2 s: tras revocar, sus 401 cuentan como keys inválidas de esta IP, y el gateway bloquea a toda la
// IP al pasar de 20 por minuto (fallos-key-por-ip). B y C comparten IP con D en esta prueba: hay que quedar debajo.
export function revocacion(data) {
  const d = data.consumidores.d;
  const etiquetas = { grupo: 'revocacion', quien: 'D' };
  const finAntes = Date.now() + 20_000;
  while (Date.now() < finAntes) {
    dAntes.add(pedirConKey(d.key, etiquetas).status === 200);
    sleep(2);
  }
  const revocar = http.del(`${BASE}/api/gateway/keys/${d.keyId}`, null, admin(data.token));
  check(revocar, { 'revocar key D responde 204': (r) => r.status === 204 });
  const finDespues = Date.now() + 20_000;
  while (Date.now() < finDespues) {
    const r = pedirConKey(d.key, etiquetas);
    dDespues.add(r.status === 401);
    if (r.status !== 401) dAceptada.add(1);
    sleep(2);
  }
}

export function teardown(data) {
  // No dejar keys activas: A, B y C se revocan (D ya lo está; repetirlo también responde 204).
  for (const c of Object.values(data.consumidores)) http.del(`${BASE}/api/gateway/keys/${c.keyId}`, null, admin(data.token));
}

export function handleSummary(datos) {
  const m = datos.metrics;
  const v = (nombre, stat) => (m[nombre] && m[nombre].values && m[nombre].values[stat] !== undefined ? m[nombre].values[stat] : null);
  const p95 = (f) => v(`http_req_duration{grupo:cumplidor,fase:${f}}`, 'p(95)');
  const base = p95('base');
  const abuso = p95('abuso');
  const resumen = {
    parametros: { base_url: BASE, abuso_req_s: ABUSO_RPS, limite_por_defecto: LIMITE_POR_DEFECTO, limite_b: LIMITE_B,
      t_base_s: T.base, t_abuso_s: T.abuso, t_post_s: T.post, max_200_abusivo: MAX_200_ABUSIVO,
      commit: __ENV.COMMIT || null, red_k6: __ENV.RED_K6 || null },
    peticiones: v('http_reqs', 'count'),
    iteraciones_descartadas: v('dropped_iterations', 'count') || 0,
    abusivo: { respuestas_200: v('abusivo_200', 'count'), respuestas_429: v('abusivo_429', 'count'),
      sin_retry_after: v('abusivo_429_sin_retry_after', 'count'), otros_estados: v('abusivo_otro_estado', 'count'),
      p95_ms: v('http_req_duration{grupo:abusivo}', 'p(95)') },
    cumplidores_pct_200: { b_limite_configurado: pct(v('cumplidor_b_200', 'rate')), c_limite_por_defecto: pct(v('cumplidor_c_200', 'rate')),
      portal: pct(v('portal_200', 'rate')) },
    cumplidores_p95_ms: { base, abuso, post: p95('post'), aumento_en_abuso_ms: base !== null && abuso !== null ? abuso - base : null },
    revocacion: { pct_200_antes: pct(v('revocada_200_antes', 'rate')), pct_401_despues: pct(v('revocada_401_despues', 'rate')),
      aceptadas_tras_revocar: v('revocada_aceptada_tras_revocar', 'count') },
    umbrales_cumplidos: Object.entries(m).every(([, x]) => !x.thresholds || Object.values(x.thresholds).every((t) => t.ok)),
  };
  const salida = __ENV.SALIDA || '/resultados/asr8-gateway.json';
  return { [salida]: JSON.stringify(resumen, null, 2), stdout: texto(resumen) };
}

function pct(rate) {
  return rate === null ? null : rate * 100;
}

function texto(r) {
  const f = (x) => (x === null || x === undefined ? 'n/d' : Number(x).toFixed(1));
  let s = `\n=== ASR8/RNF11 límite por consumidor en el gateway (A abusa a ${r.parametros.abuso_req_s} req/s) ===\n`;
  s += `peticiones=${r.peticiones} descartadas=${r.iteraciones_descartadas}\n`;
  s += `A: 200=${r.abusivo.respuestas_200} (máx ${r.parametros.max_200_abusivo}) 429=${r.abusivo.respuestas_429} `
    + `sin Retry-After=${r.abusivo.sin_retry_after} otros=${r.abusivo.otros_estados} p95=${f(r.abusivo.p95_ms)} ms\n`;
  s += `cumplidores 200: B=${f(r.cumplidores_pct_200.b_limite_configurado)}% C=${f(r.cumplidores_pct_200.c_limite_por_defecto)}% `
    + `portal=${f(r.cumplidores_pct_200.portal)}%\n`;
  s += `cumplidores p95: base=${f(r.cumplidores_p95_ms.base)} abuso=${f(r.cumplidores_p95_ms.abuso)} post=${f(r.cumplidores_p95_ms.post)} ms `
    + `(aumento ${f(r.cumplidores_p95_ms.aumento_en_abuso_ms)} ms)\n`;
  s += `revocación D: 200 antes=${f(r.revocacion.pct_200_antes)}% 401 después=${f(r.revocacion.pct_401_despues)}% `
    + `aceptadas tras revocar=${r.revocacion.aceptadas_tras_revocar}\n`;
  s += `umbrales: ${r.umbrales_cumplidos ? 'TODOS CUMPLIDOS' : 'ALGUNO FALLÓ'}\n`;
  return s;
}
