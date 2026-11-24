// ASR2: mide el tiempo de respuesta del alta real de hallazgos (p95 < 5 s).
// Por ahora mide JSON sin archivos; el servicio aún no expone una API de carga de adjuntos.
// Ejecutar desde pruebas-carga/ con TOKEN y un servicio-cientifico local levantado.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const RUTA = __ENV.RUTA || '/api/cientifico/hallazgos';
const TASA = parseInt(__ENV.R || '2');
const DURACION = __ENV.DUR || '60s';
const creados = new Counter('asr2_hallazgos_creados');
const registroCorrecto = new Rate('asr2_registro_correcto');

export const options = {
  scenarios: {
    registro: {
      executor: 'constant-arrival-rate',
      rate: TASA,
      timeUnit: '1s',
      duration: DURACION,
      preAllocatedVUs: parseInt(__ENV.PRE_VUS || '10'),
      maxVUs: parseInt(__ENV.MAX_VUS || '50'),
    },
  },
  thresholds: {
    'http_req_duration{endpoint:registro}': ['p(95)<5000'],
    'http_req_failed{endpoint:registro}': ['rate<0.01'],
    asr2_registro_correcto: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

export default function () {
  const fecha = new Date().toISOString().slice(0, 10);
  const cuerpo = {
    nombreCientifico: 'Pristimantis carga',
    familia: 'Strabomantidae',
    tipoEspecimen: 'Adulto',
    fechaRecoleccion: fecha,
    departamento: '15',
    categoriaUicn: 'NE',
    descripcion: 'Registro sintético de prueba ASR2',
  };
  const respuesta = http.post(`${BASE_URL}${RUTA}`, JSON.stringify(cuerpo), {
    headers: {
      Authorization: `Bearer ${__ENV.TOKEN}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': `asr2-${Date.now()}-${__VU}-${__ITER}`,
    },
    tags: { endpoint: 'registro' },
    timeout: '15s',
  });
  const correcto = respuesta.status === 201;
  registroCorrecto.add(correcto);
  if (correcto) creados.add(1);
  check(respuesta, { 'hallazgo creado (201)': () => correcto });
}

export function handleSummary(data) {
  const metric = (name, stat) => data.metrics[name]?.values?.[stat] ?? null;
  const peticiones = metric('http_reqs', 'count') || 0;
  const resumen = {
    parametros: {
      tasa_req_s: TASA,
      duracion: DURACION,
      base_url: BASE_URL,
      ruta: RUTA,
      con_adjuntos: false,
      commit: __ENV.COMMIT || null,
      red_k6: __ENV.RED_K6 || null,
    },
    peticiones,
    creados: metric('asr2_hallazgos_creados', 'count') || 0,
    exitos_pct: metric('asr2_registro_correcto', 'rate') === null
      ? null : metric('asr2_registro_correcto', 'rate') * 100,
    latencia_ms: {
      p50: metric('http_req_duration{endpoint:registro}', 'med'),
      p95: metric('http_req_duration{endpoint:registro}', 'p(95)'),
      p99: metric('http_req_duration{endpoint:registro}', 'p(99)'),
      max: metric('http_req_duration{endpoint:registro}', 'max'),
    },
  };
  const salida = __ENV.SALIDA || '/resultados/asr2-registro.json';
  return {
    [salida]: JSON.stringify(resumen, null, 2),
    stdout: `\n=== ASR2 registro JSON ===\npeticiones=${peticiones} creados=${resumen.creados}`
      + ` exitos=${resumen.exitos_pct?.toFixed(2) ?? 'n/d'}% p95=${resumen.latencia_ms.p95 ?? 'n/d'} ms\n`,
  };
}
