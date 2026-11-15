// HU-06 criterio 3 / CG-02 (RNF5): la búsqueda del portal público responde en <= 2 s (p95) con 10.000 fichas y la caché VACÍA.
//
// Una ejecución = UNA fase (FASE=A | B), porque la caché de Redis debe vaciarse justo antes de cada fase y k6 no puede
// hacerlo. Lo orquesta ../correr-hu06-frio.sh (vacía la caché, corre la fase A, vacía otra vez, corre la fase B).
//   Fase A: 100 búsquedas DISTINTAS, secuenciales (1 VU).
//   Fase B: otras 100 búsquedas distintas (distintas también de las de A), con 10 VUs.
// Las 200 búsquedas salen de un generador determinista (SEED) con cuotas por categoría; cada fase toma 100 con la misma
// cuota: q por género / epíteto / género+epíteto / prefijo / nombre común, familia, departamento (código y nombre),
// combinaciones de dos filtros, páginas profundas válidas (hasta el tope de 10.000 resultados), q de 2 letras (LIKE)
// y prefijos del número de ficha ("carga00*": miles de coincidencias). Como la clave de caché incluye q, familia,
// departamento, page y size, cada búsqueda distinta es un fallo de caché garantizado.
//
// Umbrales (todos deben cumplirse): p95 <= 2000 ms, 0 respuestas 5xx/sin respuesta, 100 % de respuestas 200,
// 100 iteraciones completadas, y NINGUNA búsqueda vacía (total > 0 y página con contenido).
//
// Variables: BASE_URL (def. http://127.0.0.1:18080), FASE (A|B), SEED (def. 1), FICHAS (las que hay sembradas, solo se
// registra), RUN (id de la corrida, solo se registra), SALIDA (ruta del JSON). Metadatos D42: REPLICAS, CPUS, COMMIT y
// RED_K6 los pasan correr-k6.sh / correr-hu06-frio.sh.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const FASE = (__ENV.FASE || 'A').toUpperCase();
const SEED = parseInt(__ENV.SEED || '1');
const POR_FASE = 100;
if (FASE !== 'A' && FASE !== 'B') throw new Error('FASE debe ser A o B');
const VUS = FASE === 'A' ? 1 : 10;

// ---- Vocabulario de la semilla (datos/semilla-fichas.sql) ----
const GENEROS = [
  ['atelopus', 'Bufonidae'], ['pristimantis', 'Strabomantidae'], ['rhinella', 'Bufonidae'], ['boana', 'Hylidae'],
  ['dendropsophus', 'Hylidae'], ['leptodactylus', 'Leptodactylidae'], ['hyloxalus', 'Dendrobatidae'],
  ['oophaga', 'Dendrobatidae'], ['espeletia', 'Asteraceae'], ['cattleya', 'Orchidaceae'], ['puya', 'Bromeliaceae'],
  ['passiflora', 'Passifloraceae'], ['tangara', 'Thraupidae'], ['anolis', 'Dactyloidae'], ['bothrops', 'Viperidae'],
  ['micrurus', 'Elapidae'], ['panthera', 'Felidae'], ['tremarctos', 'Ursidae'], ['quercus', 'Fagaceae'],
  ['podocarpus', 'Podocarpaceae'],
];
const FAMILIAS = ['Bufonidae', 'Strabomantidae', 'Hylidae', 'Leptodactylidae', 'Dendrobatidae', 'Asteraceae', 'Orchidaceae',
  'Bromeliaceae', 'Passifloraceae', 'Thraupidae', 'Dactyloidae', 'Viperidae', 'Elapidae', 'Felidae', 'Ursidae',
  'Fagaceae', 'Podocarpaceae'];
const EPITETOS = ['andinus', 'nebulosus', 'viridis', 'rubrum', 'aureus', 'montanus', 'paramensis', 'colombianus', 'cauca',
  'magdalenae', 'amazonicus', 'pacificus', 'orinocensis', 'caribaeus', 'sierrae', 'nigricans', 'albus', 'purpureus',
  'gigas', 'minimus', 'variabilis', 'elegans', 'tropicalis', 'silvestris', 'riparius', 'lacustris', 'cordillerae',
  'andicola', 'bogotensis', 'santanderi'];
// Nombres comunes SIN adjetivo (la semilla pone "<común> <adjetivo>": agregar otro adjetivo más abajo sí coincide)
const COMUNES = ['rana arlequín', 'rana de lluvia', 'sapo', 'rana arbórea', 'ranita', 'rana criolla', 'rana saltarina',
  'rana dardo', 'frailejón', 'orquídea', 'bromelia', 'pasionaria', 'tangará', 'lagartija', 'talla equis', 'coral',
  'jaguar', 'oso anteojos', 'roble', 'pino colombiano', 'rana'];
const ADJETIVOS = ['andino', 'verde', 'dorado', 'gigante', 'tropical', 'colombiano', 'blanco', 'enano'];
const DEPTOS = [['05', 'Antioquia'], ['08', 'Atlántico'], ['11', 'Bogotá D.C.'], ['13', 'Bolívar'], ['15', 'Boyacá'],
  ['17', 'Caldas'], ['18', 'Caquetá'], ['19', 'Cauca'], ['20', 'Cesar'], ['23', 'Córdoba'], ['25', 'Cundinamarca'],
  ['27', 'Chocó'], ['41', 'Huila'], ['44', 'La Guajira'], ['47', 'Magdalena'], ['50', 'Meta'], ['52', 'Nariño'],
  ['54', 'Norte de Santander'], ['63', 'Quindío'], ['66', 'Risaralda'], ['68', 'Santander'], ['70', 'Sucre'],
  ['73', 'Tolima'], ['76', 'Valle del Cauca'], ['81', 'Arauca'], ['85', 'Casanare'], ['86', 'Putumayo'],
  ['88', 'San Andrés y Providencia'], ['91', 'Amazonas'], ['94', 'Guainía'], ['95', 'Guaviare'], ['97', 'Vaupés'],
  ['99', 'Vichada']];
const PREFIJOS_LIKE = ['at', 'pr', 'rh', 'bo', 'de', 'le', 'hy', 'oo', 'es', 'ca', 'pu', 'pa', 'ta', 'an', 'mi', 'tr', 'qu', 'po'];
const SIZES = [10, 20, 30, 50];

// PRNG determinista (mulberry32): la misma SEED produce las mismas 200 búsquedas en cada proceso k6.
function prng(semilla) {
  let a = semilla >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rnd = prng(SEED * 7919 + 13);
const entre = (n) => Math.floor(rnd() * n);
const elige = (a) => a[entre(a.length)];
const depto = () => elige(DEPTOS);                // [codigo, nombre]
const tam = () => elige(SIZES);
const pag = (max) => entre(max + 1);              // 0..max; los grupos usados tienen >= 250 filas, así que page<=3 con size<=50 no queda vacía

// Categorías: [nombre, cuota por fase, generador de { q, familia, departamento, page, size, depto(código para la clave) }].
// Las páginas de las búsquedas con filtro combinado quedan en 0 (los grupos son chicos: ~15 filas); las de un solo
// filtro llegan a la 3.
const CATEGORIAS = [
  ['q_genero', 10, () => ({ q: elige(GENEROS)[0], page: pag(3), size: tam() })],
  ['q_epiteto', 12, () => ({ q: elige(EPITETOS), page: pag(3), size: tam() })],
  ['q_genero_epiteto', 12, () => ({ q: `${elige(GENEROS)[0]} ${elige(EPITETOS)}`, page: 0, size: tam() })],
  ['q_prefijo', 8, () => { const g = elige(GENEROS)[0]; return { q: g.slice(0, 3 + entre(3)), page: pag(2), size: tam() }; }],
  ['q_nombre_comun', 6, () => ({ q: rnd() < 0.5 ? elige(COMUNES) : `${elige(COMUNES)} ${elige(ADJETIVOS)}`, page: 0, size: tam() })],
  ['familia', 8, () => ({ familia: elige(FAMILIAS), page: pag(3), size: tam() })],
  ['departamento', 8, () => { const d = depto(); return { departamento: rnd() < 0.5 ? d[0] : d[1], codigo: d[0], page: pag(3), size: tam() }; }],
  ['q_familia', 8, () => { const g = elige(GENEROS);
    // género + SU familia, o epíteto + cualquier familia (el epíteto existe en todas); así ninguna combinación queda vacía
    return rnd() < 0.5 ? { q: g[0], familia: g[1], page: 0, size: tam() } : { q: elige(EPITETOS), familia: elige(FAMILIAS), page: 0, size: tam() }; }],
  ['q_departamento', 8, () => { const d = depto(); return { q: rnd() < 0.5 ? elige(GENEROS)[0] : elige(EPITETOS), departamento: d[0], codigo: d[0], page: 0, size: tam() }; }],
  ['familia_departamento', 6, () => { const d = depto(); return { familia: elige(FAMILIAS), departamento: d[1], codigo: d[0], page: 0, size: tam() }; }],
  ['pagina_profunda', 8, () => { const size = elige([20, 50]); const maxPage = Math.floor(10000 / size) - 1; return { page: maxPage - entre(40), size }; }],
  // "carga0"/"carga00" ~10.000/1.000 coincidencias (hasta la página 2); "carga00N"/"carga000" ~100: solo página 0
  ['q_numero_ficha', 4, () => { const q = `carga${elige(['0', '00', '000', '001', '002', '003', '004', '005', '006', '007', '008', '009'])}`; return { q, page: q.length <= 7 ? pag(2) : 0, size: tam() }; }],
  ['q_2_letras_like', 2, () => ({ q: elige(PREFIJOS_LIKE), page: pag(2), size: tam() })],
];
const sumaCuotas = CATEGORIAS.reduce((s, c) => s + c[1], 0);
if (sumaCuotas !== POR_FASE) throw new Error(`las cuotas suman ${sumaCuotas}, deben sumar ${POR_FASE}`);

const minus = (t) => (t === undefined ? '' : String(t).toLowerCase());
const clave = (b) => `${minus(b.q)}|${minus(b.familia)}|${b.codigo || ''}|${b.page || 0}|${b.size || 20}`;
function queryString(b) {
  const p = [];
  if (b.q !== undefined) p.push(`q=${encodeURIComponent(b.q)}`);
  if (b.familia !== undefined) p.push(`familia=${encodeURIComponent(b.familia)}`);
  if (b.departamento !== undefined) p.push(`departamento=${encodeURIComponent(b.departamento)}`);
  p.push(`page=${b.page}`, `size=${b.size}`);
  return p.join('&');
}

// Genera las 200 búsquedas (100 de A, luego 100 de B) sin repetir clave de caché entre las 200.
function planificar() {
  const usadas = new Set();
  const fases = { A: [], B: [] };
  for (const fase of ['A', 'B']) {
    for (const [nombre, cuota, gen] of CATEGORIAS) {
      let n = 0, intentos = 0;
      while (n < cuota) {
        if (++intentos > 5000) throw new Error(`categoría ${nombre}: no hay ${cuota} búsquedas distintas por fase`);
        const b = gen();
        const k = clave(b);
        if (usadas.has(k)) continue;
        usadas.add(k);
        fases[fase].push({ categoria: nombre, qs: queryString(b) });
        n++;
      }
    }
    // Orden barajado (Fisher-Yates con el mismo PRNG) para que las categorías no vayan en bloque
    const arr = fases[fase];
    for (let i = arr.length - 1; i > 0; i--) { const j = entre(i + 1); [arr[i], arr[j]] = [arr[j], arr[i]]; }
  }
  return fases;
}
const PLAN = planificar()[FASE].map((b, i) => ({ ...b, id: String(i).padStart(3, '0') }));

// ---- Métricas ----
const total = new Trend('resultados_busqueda');         // "total" que declara la respuesta (coincidencias)
const devueltos = new Trend('devueltos_busqueda');      // elementos en la página devuelta
const vacias = new Counter('busquedas_vacias');         // total == 0 o página sin elementos
const degradadas = new Counter('respuestas_degradadas'); // modoDegradado=true: no mediría la búsqueda completa
const errores5xx = new Counter('respuestas_5xx');       // 5xx o sin respuesta (estado 0)
const no200 = new Counter('respuestas_no_200');

http.setResponseCallback(http.expectedStatuses(200));

// Umbrales "vacíos" por búsqueda y por categoría: hacen que k6 calcule sus submétricas y las deje en el resumen.
const umbrales = {
  http_req_duration: ['p(95)<=2000'],
  respuestas_5xx: ['count==0'],
  respuestas_no_200: ['count==0'],
  busquedas_vacias: ['count==0'],
  respuestas_degradadas: ['count==0'],
  http_req_failed: ['rate==0'],
  iterations: [`count==${POR_FASE}`],
};
for (const b of PLAN) {
  umbrales[`http_req_duration{b:${b.id}}`] = ['max>=0'];
  umbrales[`resultados_busqueda{b:${b.id}}`] = ['max>=0'];
  umbrales[`devueltos_busqueda{b:${b.id}}`] = ['max>=0'];
}
for (const [nombre] of CATEGORIAS) umbrales[`http_req_duration{categoria:${nombre}}`] = ['max>=0'];

export const options = {
  scenarios: {
    [`fase_${FASE}`]: {
      executor: 'shared-iterations', vus: VUS, iterations: POR_FASE, maxDuration: '10m',
    },
  },
  thresholds: umbrales,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const b = PLAN[exec.scenario.iterationInTest];
  const res = http.get(`${BASE_URL}/api/publica/fichas?${b.qs}`, {
    tags: { b: b.id, categoria: b.categoria, name: 'GET /api/publica/fichas?...' }, timeout: '30s',
  });
  const etiquetas = { b: b.id, categoria: b.categoria };
  if (res.status >= 500 || res.status === 0) errores5xx.add(1, etiquetas);
  if (res.status !== 200) { no200.add(1, etiquetas); return; }
  let cuerpo;
  try { cuerpo = res.json(); } catch (e) { no200.add(1, etiquetas); return; }
  const t = typeof cuerpo.total === 'number' ? cuerpo.total : 0;
  const d = Array.isArray(cuerpo.content) ? cuerpo.content.length : 0;
  total.add(t, etiquetas);
  devueltos.add(d, etiquetas);
  if (t === 0 || d === 0) vacias.add(1, etiquetas);
  if (cuerpo.modoDegradado === true) degradadas.add(1, etiquetas);
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (n, s) => (m[n] && m[n].values && m[n].values[s] !== undefined ? m[n].values[s] : null);
  const c = (n) => v(n, 'count') || 0;
  const lat = (n) => ({ avg: v(n, 'avg'), min: v(n, 'min'), p50: v(n, 'med'), p90: v(n, 'p(90)'), p95: v(n, 'p(95)'), p99: v(n, 'p(99)'), max: v(n, 'max') });
  const porCategoria = {};
  for (const [nombre, cuota] of CATEGORIAS) {
    const k = `http_req_duration{categoria:${nombre}}`;
    porCategoria[nombre] = { busquedas: cuota, p50: v(k, 'med'), p95: v(k, 'p(95)'), max: v(k, 'max') };
  }
  const busquedas = PLAN.map((b) => ({
    id: b.id, categoria: b.categoria, qs: b.qs,
    ms: v(`http_req_duration{b:${b.id}}`, 'max'),
    total: v(`resultados_busqueda{b:${b.id}}`, 'max'),
    devueltos: v(`devueltos_busqueda{b:${b.id}}`, 'max'),
  }));
  const sin200 = c('respuestas_no_200'), n5 = c('respuestas_5xx'), nvac = c('busquedas_vacias');
  const peticiones = c('http_reqs');
  const p95 = v('http_req_duration', 'p(95)');
  const ok = {
    p95_menor_igual_2000_ms: p95 !== null && p95 <= 2000,
    cero_5xx: n5 === 0,
    todas_200: sin200 === 0 && peticiones === POR_FASE,
    sin_modo_degradado: c('respuestas_degradadas') === 0,
    ninguna_vacia: nvac === 0 && busquedas.every((b) => b.total > 0 && b.devueltos > 0),
  };
  const resumen = {
    // D42: parámetros, réplicas, CPU por réplica, commit, red de k6 y fecha.
    parametros: {
      caso: 'HU-06 c.3 / CG-02 (RNF5)', fase: FASE, vus: VUS, busquedas: POR_FASE, seed: SEED, run: __ENV.RUN || null,
      fichas_sembradas: __ENV.FICHAS ? parseInt(__ENV.FICHAS) : null, cache: 'vaciada (biohub:publica:*) justo antes de la fase',
      base_url: BASE_URL, replicas: __ENV.REPLICAS || null, cpus_por_replica: __ENV.CPUS || null,
      memoria_por_replica: __ENV.MEM || null, commit: __ENV.COMMIT || null, red_k6: __ENV.RED_K6 || null,
      fecha: new Date().toISOString(), nota: __ENV.NOTA || null,
    },
    peticiones,
    respuestas_no_200: sin200,
    respuestas_5xx: n5,
    exito_200_pct: peticiones ? (peticiones - sin200) / peticiones * 100 : null,
    busquedas_vacias: nvac,
    respuestas_degradadas: c('respuestas_degradadas'),
    latencia_ms: lat('http_req_duration'),
    resultados_por_busqueda: { min: v('resultados_busqueda', 'min'), p50: v('resultados_busqueda', 'med'), max: v('resultados_busqueda', 'max') },
    devueltos_por_busqueda: { min: v('devueltos_busqueda', 'min'), max: v('devueltos_busqueda', 'max') },
    umbrales: ok,
    umbrales_cumplidos: Object.values(ok).every(Boolean),
    por_categoria: porCategoria,
    busquedas,
  };
  const salida = __ENV.SALIDA || `/resultados/hu06-fase-${FASE.toLowerCase()}.json`;
  return { [salida]: JSON.stringify(resumen, null, 2), stdout: texto(resumen) };
}

function texto(r) {
  const f = (x) => (x === null || x === undefined ? 'n/d' : x.toFixed(1));
  const l = r.latencia_ms;
  let s = `\n=== HU-06 c.3 / CG-02 fase ${FASE} (${VUS} VU, ${POR_FASE} búsquedas distintas, caché fría) ===\n`;
  s += `peticiones=${r.peticiones} no-200=${r.respuestas_no_200} 5xx=${r.respuestas_5xx} vacías=${r.busquedas_vacias}\n`;
  s += `p50=${f(l.p50)} p90=${f(l.p90)} p95=${f(l.p95)} p99=${f(l.p99)} max=${f(l.max)} ms\n`;
  s += `resultados por búsqueda: min=${r.resultados_por_busqueda.min} mediana=${r.resultados_por_busqueda.p50} max=${r.resultados_por_busqueda.max}\n`;
  s += `umbrales: ${JSON.stringify(r.umbrales)} => ${r.umbrales_cumplidos ? 'CUMPLE' : 'NO CUMPLE'}\n`;
  return s;
}
