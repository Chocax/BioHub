#!/usr/bin/env bash
# Mediana de las corridas de HU-06 c.3 / CG-02: lee resultados/hu06-corrida<N>-fase-{a,b}.json (los descartado-* y los
# informativos NO entran) y escribe resultados/hu06-resumen-medianas.json con, por fase, p50/p90/p95/p99/max de cada
# corrida y su mediana, errores y el veredicto (cada corrida y la mediana deben cumplir p95 <= 2000 ms, 0 5xx, 100 % 200).
set -euo pipefail
cd "$(dirname "$0")/resultados"
python3 -I - <<'PY'
import glob, json, re, statistics as st
filas = {'a': {}, 'b': {}}
for f in sorted(glob.glob('hu06-corrida*-fase-[ab].json')):
    m = re.match(r'hu06-corrida(\d+)-fase-([ab])\.json$', f)
    filas[m.group(2)][int(m.group(1))] = json.load(open(f, encoding='utf8'))
claves = ['p50', 'p90', 'p95', 'p99', 'max']
out = {'caso': 'HU-06 c.3 / CG-02 (RNF5)', 'umbral_p95_ms': 2000, 'fases': {}}
ok_total = True
for fase, runs in filas.items():
    if not runs: continue
    corridas = []
    for n, d in sorted(runs.items()):
        corridas.append({'corrida': n, **{k: d['latencia_ms'][k] for k in claves}, 'peticiones': d['peticiones'],
                         'no_200': d['respuestas_no_200'], '5xx': d['respuestas_5xx'], 'vacias': d['busquedas_vacias'],
                         'resultados_min': d['resultados_por_busqueda']['min'], 'resultados_mediana': d['resultados_por_busqueda']['p50'],
                         'resultados_max': d['resultados_por_busqueda']['max'], 'umbrales_cumplidos': d['umbrales_cumplidos']})
    mediana = {k: st.median(c[k] for c in corridas) for k in claves}
    ok = all(c['umbrales_cumplidos'] for c in corridas) and mediana['p95'] <= 2000
    ok_total &= ok
    p = next(iter(runs.values()))['parametros']
    out['fases'][fase.upper()] = {'vus': p['vus'], 'corridas': corridas, 'mediana': mediana, 'cumple': ok}
p = next(iter(filas['a'].values()))['parametros']
out['entorno'] = {k: p[k] for k in ('fichas_sembradas', 'replicas', 'cpus_por_replica', 'memoria_por_replica', 'commit', 'red_k6', 'base_url')}
out['entorno']['fechas'] = sorted(d['parametros']['fecha'] for r in filas.values() for d in r.values())
out['veredicto'] = 'CUMPLE' if ok_total else 'NO CUMPLE'
json.dump(out, open('hu06-resumen-medianas.json', 'w', encoding='utf8'), indent=2, ensure_ascii=False)
print(f"{'fase':4} {'corr':>4} {'p50':>7} {'p90':>7} {'p95':>7} {'p99':>7} {'max':>7}")
for fase, d in out['fases'].items():
    for c in d['corridas']:
        print(f"{fase:4} {c['corrida']:>4} " + ' '.join(f"{c[k]:7.1f}" for k in claves) + f"  no200={c['no_200']} 5xx={c['5xx']} vacias={c['vacias']} res[min/med/max]={c['resultados_min']:.0f}/{c['resultados_mediana']:.0f}/{c['resultados_max']:.0f}")
    print(f"{fase:4} {'med':>4} " + ' '.join(f"{d['mediana'][k]:7.1f}" for k in claves))
print('Veredicto:', out['veredicto'])
PY
