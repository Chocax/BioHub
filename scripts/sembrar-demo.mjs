// Datos de ejemplo para probar BioHub a mano: hallazgos, una ficha con atributos, una reclasificación,
// equipos, un consumidor de la API externa y un reporte. Todo entra por el gateway, como lo haría la web,
// así que también se llena el portal público (por eventos) y la base analítica.
//
// Uso (lo llama levantar.ps1 -Sembrar; también sirve solo):
//   $env:BIOHUB_CLAVE_PRUEBA = "<KEYCLOAK_TEST_USERS_PASSWORD>"; node scripts/sembrar-demo.mjs
// Variables opcionales: BIOHUB_GATEWAY (http://localhost:8080) y BIOHUB_KEYCLOAK (http://localhost:8180).
//
// Se puede correr varias veces: si ya hay hallazgos no crea más, y los equipos repetidos se saltan.
// Solo para el entorno local: nunca contra Aiven ni contra datos reales.

const GATEWAY = process.env.BIOHUB_GATEWAY ?? 'http://localhost:8080';
const KEYCLOAK = process.env.BIOHUB_KEYCLOAK ?? 'http://localhost:8180';
const CLAVE = process.env.BIOHUB_CLAVE_PRUEBA;

if (!CLAVE) {
  console.error('Falta BIOHUB_CLAVE_PRUEBA (la contraseña de los usuarios de prueba, KEYCLOAK_TEST_USERS_PASSWORD).');
  process.exit(2);
}

async function token(usuario) {
  const r = await fetch(`${KEYCLOAK}/realms/biohub/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'biohub-pruebas', username: usuario, password: CLAVE }),
  });
  if (!r.ok) throw new Error(`Keycloak no dio token a ${usuario} (${r.status}). ¿La contraseña de prueba coincide con la del realm?`);
  return (await r.json()).access_token;
}

async function pedir(metodo, ruta, tk, cuerpo) {
  const r = await fetch(GATEWAY + ruta, {
    method: metodo,
    headers: { Authorization: `Bearer ${tk}`, ...(cuerpo !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: cuerpo !== undefined ? JSON.stringify(cuerpo) : undefined,
  });
  const texto = await r.text();
  let datos = null;
  try { datos = texto ? JSON.parse(texto) : null; } catch { datos = texto; }
  return { status: r.status, datos };
}

// Fechas relativas a hoy: los dos meses anteriores, para que el reporte del mes pasado tenga datos y tendencia.
const hoy = new Date();
const mes = (atras, dia) => {
  const d = new Date(hoy.getFullYear(), hoy.getMonth() - atras, Math.min(dia, 28));
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const periodoAnterior = mes(1, 1).slice(0, 7);
const azar = (base) => Math.round((base + (Math.random() - 0.5) * 0.4) * 1e5) / 1e5;

const ESPECIES = [
  ['Oophaga histrionica', 'Rana venenosa arlequín', 'Dendrobatidae', 'Chocó', 'CR', 5.69, -76.65, { longitud_hocico: 32.5, temperatura_agua: 24.1, canto_registrado: true }],
  ['Ara macao', 'Guacamaya bandera', 'Psittacidae', 'Meta', 'LC', 3.9, -73.1],
  ['Tremarctos ornatus', 'Oso de anteojos', 'Ursidae', 'Boyacá', 'VU', 5.8, -73.0],
  ['Atelopus nahumae', 'Rana arlequín de la Sierra', 'Bufonidae', 'Magdalena', 'CR', 10.9, -73.9],
  ['Espeletia grandiflora', 'Frailejón', 'Asteraceae', 'Cundinamarca', 'LC', 4.6, -73.9],
  ['Cattleya trianae', 'Flor de mayo', 'Orchidaceae', 'Tolima', 'EN', 4.4, -75.2],
  ['Saguinus oedipus', 'Tití cabeciblanco', 'Callitrichidae', 'Bolívar', 'CR', 10.1, -75.3],
  ['Inia geoffrensis', 'Delfín rosado', 'Iniidae', 'Amazonas', 'EN', -3.8, -70.3],
  ['Rupicola peruvianus', 'Gallito de roca', 'Cotingidae', 'Antioquia', 'LC', 6.2, -75.6],
  ['Tapirus pinchaque', 'Danta de montaña', 'Tapiridae', 'Huila', 'EN', 2.0, -76.0],
];

const EQUIPOS = [
  ['DRN-0101', 'Dron cartográfico Andes', 'Dron'],
  ['CAM-0207', 'Cámara trampa Chocó 7', 'Cámara trampa'],
  ['HID-0033', 'Hidrófono Amazonas', 'Hidrófono'],
  ['SEN-0412', 'Sensor de humedad páramo', 'Sensor'],
];

async function main() {
  const [investigador, alto, admin, analista] = await Promise.all(
    ['investigador1', 'cientifico1', 'admin1', 'analista1'].map(token),
  );
  console.log('  tokens de los usuarios de prueba: ok');

  const existentes = await pedir('GET', '/api/cientifico/ubicacion/hallazgos?page=0&size=1', investigador);
  if (existentes.status !== 200) throw new Error(`La búsqueda respondió ${existentes.status}: ¿están arriba el gateway y el servicio científico?`);

  if ((existentes.datos?.total ?? 0) > 0) {
    console.log(`  ya hay ${existentes.datos.total} hallazgos: no creo más`);
  } else {
    const ficha = await pedir('PUT', '/api/cientifico/fichas/Oophaga%20histrionica/atributos', alto, [
      { clave: 'longitud_hocico', etiqueta: 'Longitud hocico-cloaca', tipo: 'NUMERO', obligatorio: true, habilitado: true, unidad: 'mm' },
      { clave: 'temperatura_agua', etiqueta: 'Temperatura del agua', tipo: 'NUMERO', obligatorio: false, habilitado: true, unidad: '°C' },
      { clave: 'canto_registrado', etiqueta: 'Canto registrado', tipo: 'BOOLEANO', obligatorio: false, habilitado: true, unidad: null },
    ]);
    console.log(`  ficha de Oophaga histrionica con atributos: ${ficha.status}`);

    const ids = [];
    for (const [i, [cientifico, comun, familia, departamento, uicn, lat, lon, atributos]] of ESPECIES.entries()) {
      for (const atras of [2, 1]) {
        const r = await pedir('POST', '/api/cientifico/hallazgos', investigador, {
          nombreCientifico: cientifico, nombreComun: comun, familia, tipoEspecimen: 'Adulto',
          fechaRecoleccion: mes(atras, (i * 2) % 27 + 1), departamento, categoriaUicn: uicn,
          descripcion: `Registro de expedición en ${departamento}.`,
          latitud: azar(lat), longitud: azar(lon), atributos: atributos ?? null,
        });
        if (r.status === 201) ids.push(r.datos.hallazgoId);
        else console.log(`  no se creó ${cientifico}: ${r.status} ${JSON.stringify(r.datos).slice(0, 160)}`);
      }
    }
    console.log(`  hallazgos creados: ${ids.length}`);

    if (ids[4]) {
      const re = await pedir('PUT', `/api/cientifico/hallazgos/${ids[4]}`, alto, {
        nombreComun: 'Oso andino', categoriaUicn: 'EN',
        justificacion: 'Revisión de la categoría UICN tras el censo poblacional.',
      });
      console.log(`  reclasificación de ejemplo (para ver el historial): ${re.status}`);
    }
  }

  let equiposNuevos = 0;
  for (const [codigoInventario, nombre, categoria] of EQUIPOS) {
    const r = await pedir('POST', '/api/equipos', admin, { codigoInventario, nombre, categoria });
    if (r.status === 201) equiposNuevos += 1;
  }
  console.log(`  equipos nuevos: ${equiposNuevos} (los que ya existían se saltan)`);

  const consumidores = await pedir('GET', '/api/gateway/consumidores?page=0&size=50', admin);
  if (!(consumidores.datos?.content ?? []).some((c) => c.nombre === 'Universidad Nacional de Colombia')) {
    const c = await pedir('POST', '/api/gateway/consumidores', admin, { nombre: 'Universidad Nacional de Colombia', contactoEmail: 'datos@ejemplo.org' });
    console.log(`  consumidor de la API externa: ${c.status}`);
  }

  if (process.env.BIOHUB_SIN_FOTOS !== '1') {
    await agregarFotos(investigador);
  }

  // El reporte se calcula en la base analítica, que se llena por eventos: se espera un momento a que lleguen.
  await new Promise((listo) => setTimeout(listo, 3000));
  const reportes = await pedir('GET', '/api/reportes/solicitudes', analista);
  if (!(Array.isArray(reportes.datos) && reportes.datos.some((s) => s.periodo === periodoAnterior))) {
    const rep = await pedir('POST', '/api/reportes/solicitudes', analista, { periodo: periodoAnterior });
    console.log(`  reporte de ${periodoAnterior}: ${rep.status}`);
  }
  console.log('  datos de ejemplo listos');
}

// ------------------------------------------------------------------------------------------- fotos de ejemplo
// A cada especie sin foto se le busca una fotografía con licencia libre en Wikimedia Commons, se sube como evidencia
// de su hallazgo más reciente (el servicio le quita los metadatos) y se marca como pública (D65). El autor y la
// licencia quedan en el nombre del archivo. Si no hay internet o Commons no responde, se sigue sin fotos.
const COMMONS = 'https://commons.wikimedia.org/w/api.php';
const AGENTE = { 'User-Agent': 'BioHub-datos-de-ejemplo/1.0 (proyecto academico; carga local de prueba)' };
const pausa = (ms) => new Promise((listo) => setTimeout(listo, ms));

async function pedirCommons(url, intentos = 4) {
  for (let i = 0; i < intentos; i += 1) {
    const r = await fetch(url, { headers: AGENTE });
    if (r.status !== 429) return r;
    await pausa(4000 * (i + 1));
  }
  return null;
}

const sinEtiquetas = (html) => (html ?? '').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();

async function fotoDeCommons(nombreCientifico) {
  const consulta = new URLSearchParams({
    action: 'query', format: 'json', generator: 'search', gsrnamespace: '6', gsrlimit: '6',
    gsrsearch: `"${nombreCientifico}" filetype:bitmap`, prop: 'imageinfo', iiprop: 'url|mime|extmetadata', iiurlwidth: '1000',
  });
  const r = await pedirCommons(`${COMMONS}?${consulta}`);
  if (!r?.ok) return null;
  const paginas = Object.values((await r.json())?.query?.pages ?? {}).sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
  for (const p of paginas) {
    const info = p.imageinfo?.[0];
    if (!info || !['image/jpeg', 'image/png'].includes(info.mime) || !info.thumburl) continue;
    const licencia = sinEtiquetas(info.extmetadata?.LicenseShortName?.value) || 'licencia libre';
    const autor = sinEtiquetas(info.extmetadata?.Artist?.value).slice(0, 60) || 'Wikimedia Commons';
    const imagen = await pedirCommons(info.thumburl);
    if (!imagen?.ok) continue;
    const extension = info.mime === 'image/png' ? 'png' : 'jpg';
    const nombre = `${nombreCientifico} - ${autor} (${licencia}, Wikimedia Commons).${extension}`.replace(/[\\/:*?"<>|]/g, '');
    return { bytes: await imagen.arrayBuffer(), mime: info.mime, nombre };
  }
  return null;
}

async function agregarFotos(investigador) {
  let agregadas = 0;
  let sinResultado = 0;
  for (let page = 0; page < 20; page += 1) {
    const catalogo = await pedir('GET', `/api/cientifico/especies?page=${page}&size=48`, investigador);
    if (catalogo.status !== 200) {
      console.log(`  no se pudo leer el catálogo de especies (${catalogo.status}): sigo sin fotos`);
      return;
    }
    for (const especie of catalogo.datos.content) {
      if (especie.foto || especie.registros === 0) continue;
      const hallazgos = await pedir('GET', `/api/cientifico/ubicacion/hallazgos?especie=${encodeURIComponent(especie.nombreCientifico)}&page=0&size=10`, investigador);
      const hallazgoId = (hallazgos.datos?.content ?? [])
        .find((h) => h.nombreCientifico?.toLowerCase() === especie.nombreCientifico.toLowerCase())?.id;
      if (!hallazgoId) continue;
      let foto = null;
      try {
        foto = await fotoDeCommons(especie.nombreCientifico);
      } catch {
        console.log('  sin conexión con Wikimedia Commons: sigo sin fotos');
        return;
      }
      if (!foto) { sinResultado += 1; continue; }
      const formulario = new FormData();
      formulario.append('archivo', new Blob([foto.bytes], { type: foto.mime }), foto.nombre);
      const subida = await fetch(`${GATEWAY}/api/cientifico/hallazgos/${hallazgoId}/evidencias`, {
        method: 'POST', headers: { Authorization: `Bearer ${investigador}` }, body: formulario,
      });
      if (!subida.ok) { console.log(`  no se subió la foto de ${especie.nombreCientifico}: ${subida.status}`); continue; }
      const evidencia = await subida.json();
      await pedir('PUT', `/api/cientifico/hallazgos/${hallazgoId}/evidencias/${evidencia.id}/publica`, investigador, { publica: true });
      agregadas += 1;
      await pausa(1500); // Commons pide no saturar su API
    }
    if (page + 1 >= catalogo.datos.totalPaginas) break;
  }
  console.log(`  fotos de ejemplo agregadas: ${agregadas}${sinResultado ? ` (sin foto en Commons: ${sinResultado})` : ''}`);
}

main().catch((e) => {
  console.error(`  no se pudieron sembrar los datos: ${e.message}`);
  process.exit(1);
});
