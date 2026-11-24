import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const RUTA_REGISTRO = __ENV.RUTA_REGISTRO || '/api/cientifico/hallazgos';
const RUTA_REPORTE = __ENV.RUTA_REPORTE || '/api/reportes/exportar/csv';
const MODO = __ENV.MODO || 'base'; // 'base' o 'con_reporte'
const ADJUNTO = open('./lib/adjunto-ejemplo.bin', 'b');

const latenciaRegistro = new Trend('latencia_registro_ms', true);

export const options = {
  scenarios: MODO === 'con_reporte'
    ? {
      registro_hallazgos: {
        executor: 'constant-arrival-rate',
        rate: 2,
        timeUnit: '1s',
        duration: '60s',
        preAllocatedVUs: 10,
        maxVUs: 40,
        exec: 'registrarHallazgo',
      },
      reporte_pesado: {
        executor: 'constant-vus',
        vus: 4,
        duration: '60s',
        exec: 'ejecutarReportePesado',
      },
    }
    : {
      registro_hallazgos: {
        executor: 'constant-arrival-rate',
        rate: 2,
        timeUnit: '1s',
        duration: '60s',
        preAllocatedVUs: 10,
        maxVUs: 40,
        exec: 'registrarHallazgo',
      },
    },
  thresholds: {
    latencia_registro_ms: ['p(95)<5000'],
    http_req_failed: ['rate<0.01'],
  },
};

export function registrarHallazgo() {
  const payload = {
    nombreCientifico: 'Pristimantis rnf3',
    familia: 'Strabomantidae',
    fechaRecoleccion: '2026-03-14',
    estadoConservacion: 'NO_AMENAZADA',
    departamento: 'Boyacá',
    archivo: http.file(ADJUNTO, 'adjunto.bin', 'application/octet-stream'),
  };
  const res = http.post(`${BASE_URL}${RUTA_REGISTRO}`, payload, {
    headers: { Authorization: `Bearer ${__ENV.TOKEN_INVESTIGADOR}` },
    timeout: '15s',
  });
  latenciaRegistro.add(res.timings.duration);
  check(res, { 'registro 201': (r) => r.status === 201 });
}

export function ejecutarReportePesado() {
  const res = http.get(`${BASE_URL}${RUTA_REPORTE}`, {
    headers: { Authorization: `Bearer ${__ENV.TOKEN_ANALISTA}` },
    timeout: '30s',
  });
  check(res, { 'reporte 200': (r) => r.status === 200 });
  sleep(0.2);
}
