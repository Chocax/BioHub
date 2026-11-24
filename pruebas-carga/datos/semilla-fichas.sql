-- Semilla SOLO PARA PRUEBAS DE CARGA (no es migración). Se aplica con datos/sembrar.sh y se borra con datos/limpiar.sh.
-- Todas las fichas de carga tienen hallazgo_id que empieza por c0a9a000 (UUID c0a9a000-0000-4000-8000-<12 hex>);
-- los eventos hallazgo.* de carga-cientifica-simultanea.js usan el mismo prefijo, así que un único DELETE los cubre.
-- No toca las fichas reales/de demostración (otro prefijo).
-- Total parametrizable: sembrar.sh define @total (FICHAS=10000 ./datos/sembrar.sh); sin valor, 2.000 (ASR1/ASR4).
-- Variedad (búsquedas realistas): 20 géneros con su familia y nombre común, 30 epítetos con su adjetivo, 33 departamentos;
-- 20 % EN_PELIGRO; sin coordenadas. Tope de @total: 99.999 (profundidad de la CTE y ancho del número "cargaNNNNN").
SET SESSION cte_max_recursion_depth = 100000;
SET @total = COALESCE(@total, 2000);

INSERT INTO ficha_publica
  (hallazgo_id, version, nombre_cientifico, nombre_comun, familia, tipo_especimen, fecha_recoleccion,
   estado_conservacion, departamento_codigo, departamento_nombre, region_nombre, descripcion)
WITH RECURSIVE n (i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @total
),
deptos AS (
  SELECT codigo_dane, nombre, region, ROW_NUMBER() OVER (ORDER BY codigo_dane) AS rn, COUNT(*) OVER () AS cnt
  FROM departamento
),
generos AS (
  SELECT 0 AS k, 'Atelopus' AS g, 'Bufonidae' AS f, 'Rana arlequín' AS c
  UNION ALL SELECT 1, 'Pristimantis', 'Strabomantidae', 'Rana de lluvia'
  UNION ALL SELECT 2, 'Rhinella', 'Bufonidae', 'Sapo'
  UNION ALL SELECT 3, 'Boana', 'Hylidae', 'Rana arbórea'
  UNION ALL SELECT 4, 'Dendropsophus', 'Hylidae', 'Ranita'
  UNION ALL SELECT 5, 'Leptodactylus', 'Leptodactylidae', 'Rana criolla'
  UNION ALL SELECT 6, 'Hyloxalus', 'Dendrobatidae', 'Rana saltarina'
  UNION ALL SELECT 7, 'Oophaga', 'Dendrobatidae', 'Rana dardo'
  UNION ALL SELECT 8, 'Espeletia', 'Asteraceae', 'Frailejón'
  UNION ALL SELECT 9, 'Cattleya', 'Orchidaceae', 'Orquídea'
  UNION ALL SELECT 10, 'Puya', 'Bromeliaceae', 'Bromelia'
  UNION ALL SELECT 11, 'Passiflora', 'Passifloraceae', 'Pasionaria'
  UNION ALL SELECT 12, 'Tangara', 'Thraupidae', 'Tangará'
  UNION ALL SELECT 13, 'Anolis', 'Dactyloidae', 'Lagartija'
  UNION ALL SELECT 14, 'Bothrops', 'Viperidae', 'Talla equis'
  UNION ALL SELECT 15, 'Micrurus', 'Elapidae', 'Coral'
  UNION ALL SELECT 16, 'Panthera', 'Felidae', 'Jaguar'
  UNION ALL SELECT 17, 'Tremarctos', 'Ursidae', 'Oso de anteojos'
  UNION ALL SELECT 18, 'Quercus', 'Fagaceae', 'Roble'
  UNION ALL SELECT 19, 'Podocarpus', 'Podocarpaceae', 'Pino colombiano'
),
epitetos AS (
  SELECT 0 AS k, 'andinus' AS e, 'andino' AS a
  UNION ALL SELECT 1, 'nebulosus', 'de niebla' UNION ALL SELECT 2, 'viridis', 'verde'
  UNION ALL SELECT 3, 'rubrum', 'rojo' UNION ALL SELECT 4, 'aureus', 'dorado'
  UNION ALL SELECT 5, 'montanus', 'de montaña' UNION ALL SELECT 6, 'paramensis', 'de páramo'
  UNION ALL SELECT 7, 'colombianus', 'colombiano' UNION ALL SELECT 8, 'cauca', 'del Cauca'
  UNION ALL SELECT 9, 'magdalenae', 'del Magdalena' UNION ALL SELECT 10, 'amazonicus', 'amazónico'
  UNION ALL SELECT 11, 'pacificus', 'del Pacífico' UNION ALL SELECT 12, 'orinocensis', 'del Orinoco'
  UNION ALL SELECT 13, 'caribaeus', 'caribeño' UNION ALL SELECT 14, 'sierrae', 'de la sierra'
  UNION ALL SELECT 15, 'nigricans', 'oscuro' UNION ALL SELECT 16, 'albus', 'blanco'
  UNION ALL SELECT 17, 'purpureus', 'morado' UNION ALL SELECT 18, 'gigas', 'gigante'
  UNION ALL SELECT 19, 'minimus', 'enano' UNION ALL SELECT 20, 'variabilis', 'variable'
  UNION ALL SELECT 21, 'elegans', 'elegante' UNION ALL SELECT 22, 'tropicalis', 'tropical'
  UNION ALL SELECT 23, 'silvestris', 'silvestre' UNION ALL SELECT 24, 'riparius', 'de ribera'
  UNION ALL SELECT 25, 'lacustris', 'de laguna' UNION ALL SELECT 26, 'cordillerae', 'de cordillera'
  UNION ALL SELECT 27, 'andicola', 'andícola' UNION ALL SELECT 28, 'bogotensis', 'bogotano'
  UNION ALL SELECT 29, 'santanderi', 'santandereano'
)
SELECT UNHEX(CONCAT('c0a9a000', '0000', '4000', '8000', LPAD(HEX(n.i), 12, '0'))),
       1,
       CONCAT(g.g, ' ', ep.e, ' carga', LPAD(n.i, 5, '0')),
       CONCAT(g.c, ' ', ep.a),
       g.f,
       'Adulto',
       DATE_ADD('2025-01-01', INTERVAL (n.i % 365) DAY),
       CASE WHEN n.i % 5 = 0 THEN 'EN_PELIGRO' WHEN n.i % 5 = 1 THEN 'SIN_EVALUAR' ELSE 'NO_AMENAZADA' END,
       d.codigo_dane, d.nombre, d.region,
       CONCAT('Ficha sintética de prueba de carga número ', n.i, '. Descripción pública sin datos sensibles.')
FROM n
JOIN generos g ON g.k = n.i % 20
JOIN epitetos ep ON ep.k = (n.i DIV 20) % 30
JOIN deptos d ON d.rn = (n.i % d.cnt) + 1;

SELECT COUNT(*) AS fichas_de_carga FROM ficha_publica WHERE HEX(hallazgo_id) LIKE 'C0A9A000%';
