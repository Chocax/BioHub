import { useEffect, useId, useRef, useState } from 'react';
import { Camera, Leaf, MapPin, Save, X } from 'lucide-react';
import { refrescarSugerencias, useSugerencias } from '../api/sugerencias.js';
import { useNavigate } from 'react-router';
import { api, ErrorApi } from '../api/cliente.js';
import { DEPARTAMENTOS } from '../ubicacion/datos/departamentos.js';
import CamposAtributos, { prepararAtributos } from '../componentes/CamposAtributos.jsx';
import { AvisoError, CabeceraPagina, Campo, formatearTamano } from '../componentes/ui.jsx';

export const CATEGORIAS_UICN = [
  ['CR', 'CR · En peligro crítico'], ['EN', 'EN · En peligro'], ['VU', 'VU · Vulnerable'], ['NT', 'NT · Casi amenazada'],
  ['LC', 'LC · Preocupación menor'], ['DD', 'DD · Datos insuficientes'], ['NE', 'NE · No evaluada'],
];
const FORMATOS = '.jpg,.jpeg,.png,.wav,.mp3,.ogg,.flac,image/jpeg,image/png,audio/*';
const MAXIMO = 9 * 1024 * 1024;
const VACIO = { nombreCientifico: '', nombreComun: '', familia: '', tipoEspecimen: '', fechaRecoleccion: '', departamento: '',
  categoriaUicn: 'NE', descripcion: '', latitud: '', longitud: '' };
const hoy = () => new Date().toLocaleDateString('en-CA', { timeZone: 'America/Bogota' });

/** HU-01: registrar un hallazgo con su ficha flexible (HU-02) y sus evidencias (RF1). */
/** Vista previa local de lo que se va a subir: foto como imagen, audio con su reproductor. */
function Miniatura({ archivo }) {
  const [url, setUrl] = useState(null);
  useEffect(() => {
    const u = URL.createObjectURL(archivo);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [archivo]);
  if (!url) return <div className="evidencia__marco" aria-hidden="true" />;
  return archivo.type.startsWith('image/')
    ? <img className="evidencia__marco" src={url} alt={`Vista previa de ${archivo.name}`} />
    : <audio className="evidencia__audio" controls src={url} aria-label={`Audio: ${archivo.name}`} />;
}

export default function NuevoHallazgo() {
  const navegar = useNavigate();
  const [datos, setDatos] = useState(VACIO);
  const [plantilla, setPlantilla] = useState(null);
  const [atributos, setAtributos] = useState({});
  const [archivos, setArchivos] = useState([]);
  const [errores, setErrores] = useState({});
  const [error, setError] = useState(null);
  const [enviando, setEnviando] = useState(false);
  const [progreso, setProgreso] = useState(null);
  const ultimaEspecie = useRef('');
  // Idempotency-Key del registro (HU-01): la misma mientras no cambien los datos, para que reenviar tras un error de
  // red no cree el hallazgo dos veces; si el usuario corrige algo, el siguiente envío lleva una clave nueva.
  const claveRegistro = useRef(null);
  useEffect(() => { claveRegistro.current = null; }, [datos, atributos]);

  const cambiar = (campo) => (e) => setDatos((d) => ({ ...d, [campo]: e.target.value }));
  const sugerencias = useSugerencias();
  const idListas = useId();
  // Al elegir una especie ya registrada se completan nombre común y familia (si están vacíos); se pueden cambiar.
  const cambiarEspecie = (e) => {
    const valor = e.target.value;
    const conocida = sugerencias?.especies?.find((x) => x.nombreCientifico.toLowerCase() === valor.trim().toLowerCase());
    setDatos((d) => ({
      ...d, nombreCientifico: valor,
      nombreComun: conocida && !d.nombreComun ? (conocida.nombreComun ?? '') : d.nombreComun,
      familia: conocida && !d.familia ? (conocida.familia ?? '') : d.familia,
    }));
  };

  // Al escribir la especie se busca su plantilla: el formulario muestra los campos que esa especie mide hoy.
  useEffect(() => {
    const especie = datos.nombreCientifico.trim();
    if (especie.length < 3) { setPlantilla(null); return undefined; }
    const control = new AbortController();
    const espera = setTimeout(() => {
      ultimaEspecie.current = especie;
      api.get(`/api/cientifico/fichas/${encodeURIComponent(especie)}/atributos`, { senal: control.signal })
        .then((p) => { if (ultimaEspecie.current === especie) setPlantilla(p); })
        .catch((e) => { if (e.name !== 'AbortError') setPlantilla(null); });
    }, 450);
    return () => { clearTimeout(espera); control.abort(); };
  }, [datos.nombreCientifico]);

  function agregarArchivos(lista) {
    const nuevos = Array.from(lista).map((archivo) => ({
      archivo, error: archivo.size > MAXIMO ? 'Supera 9 MB' : null,
    }));
    setArchivos((a) => [...a, ...nuevos]);
  }

  function validar() {
    const e = {};
    if (!datos.nombreCientifico.trim()) e.nombreCientifico = 'Obligatorio';
    if (!datos.familia.trim()) e.familia = 'Obligatorio';
    if (!datos.tipoEspecimen.trim()) e.tipoEspecimen = 'Obligatorio';
    if (!datos.fechaRecoleccion) e.fechaRecoleccion = 'Obligatorio';
    else if (datos.fechaRecoleccion > hoy()) e.fechaRecoleccion = 'No puede ser una fecha futura';
    if (!datos.departamento) e.departamento = 'Obligatorio';
    const conLat = datos.latitud !== ''; const conLon = datos.longitud !== '';
    if (conLat !== conLon) e.latitud = 'Escribe latitud y longitud, o ninguna';
    if (conLat && (Number(datos.latitud) < -90 || Number(datos.latitud) > 90)) e.latitud = 'Entre -90 y 90';
    if (conLon && (Number(datos.longitud) < -180 || Number(datos.longitud) > 180)) e.longitud = 'Entre -180 y 180';
    const { resultado, errores: deAtributos } = prepararAtributos(plantilla?.atributos, atributos);
    return { e: { ...e, ...deAtributos }, atributosListos: resultado };
  }

  async function enviar(evento) {
    evento.preventDefault();
    setError(null);
    const { e, atributosListos } = validar();
    setErrores(e);
    if (Object.keys(e).length) {
      // HU-01 c.3: el mensaje que pide el criterio de aceptación
      setError(new Error('Campos obligatorios incompletos. Revisa los campos marcados.'));
      return;
    }
    setEnviando(true);
    try {
      const cuerpo = {
        ...datos,
        nombreComun: datos.nombreComun.trim() || null,
        descripcion: datos.descripcion.trim() || null,
        latitud: datos.latitud === '' ? null : Number(datos.latitud),
        longitud: datos.longitud === '' ? null : Number(datos.longitud),
        atributos: Object.keys(atributosListos).length ? atributosListos : null,
      };
      claveRegistro.current ??= crypto.randomUUID();
      const { hallazgoId } = await api.post('/api/cientifico/hallazgos', cuerpo,
        { cabeceras: { 'Idempotency-Key': claveRegistro.current } });
      const validos = archivos.filter((a) => !a.error);
      const fallidos = [];
      for (let i = 0; i < validos.length; i += 1) {
        setProgreso(`Subiendo evidencia ${i + 1} de ${validos.length}…`);
        const formulario = new FormData();
        formulario.append('archivo', validos[i].archivo);
        try {
          await api.subir(`/api/cientifico/hallazgos/${hallazgoId}/evidencias`, formulario);
        } catch (falla) {
          fallidos.push(`${validos[i].archivo.name}: ${falla.message}`);
        }
      }
      refrescarSugerencias();
      navegar(`/hallazgos/${hallazgoId}`, { state: { creado: true, fallidos } });
    } catch (falla) {
      if (falla instanceof ErrorApi && falla.errores.length) {
        setErrores(Object.fromEntries(falla.errores.map((x) => [x.campo, x.mensaje])));
      }
      setError(falla);
    } finally {
      setEnviando(false);
      setProgreso(null);
    }
  }

  return (
    <section className="pagina">
      <CabeceraPagina pestana="Registrar hallazgo" eyebrow="Hallazgos · HU-01" titulo={<>Registrar un <em>hallazgo</em></>} pintura="auto"
        descripcion="Los campos con * son obligatorios. Las coordenadas se cifran antes de guardarse y nunca salen hacia el portal público." />
      <form className="formulario" onSubmit={enviar} noValidate>
        <AvisoError error={error} titulo="No se guardó el hallazgo" />
        <div className="tarjeta">
          <p className="tarjeta__titulo titulo-icono"><Leaf className="icono" aria-hidden="true" />Espécimen</p>
          <div className="campos" style={{ marginTop: 16 }}>
            <Campo etiqueta="Nombre científico" obligatorio error={errores.nombreCientifico}
              ayuda={plantilla ? `Ficha v${plantilla.version} con ${plantilla.atributos.filter((a) => a.habilitado).length} atributos` : 'Busca la ficha de la especie al escribir'}>
              {(p) => <input {...p} value={datos.nombreCientifico} onChange={cambiarEspecie} lang="la" autoComplete="off" list={`${idListas}-especies`} placeholder="Elige de la lista o escribe una nueva" />}
            </Campo>
            <Campo etiqueta="Nombre común">{(p) => <input {...p} value={datos.nombreComun} onChange={cambiar('nombreComun')} />}</Campo>
            <Campo etiqueta="Familia" obligatorio error={errores.familia} ayuda="Elige una registrada o escribe una nueva">
              {(p) => <input {...p} value={datos.familia} onChange={cambiar('familia')} list={`${idListas}-familias`} autoComplete="off" />}
            </Campo>
            <Campo etiqueta="Tipo de espécimen" obligatorio error={errores.tipoEspecimen} ayuda="Adulto, juvenil, huevo, muestra de tejido…">
              {(p) => <input {...p} value={datos.tipoEspecimen} onChange={cambiar('tipoEspecimen')} />}
            </Campo>
            <Campo etiqueta="Fecha de recolección" obligatorio error={errores.fechaRecoleccion}>
              {(p) => <input {...p} type="date" max={hoy()} value={datos.fechaRecoleccion} onChange={cambiar('fechaRecoleccion')} />}
            </Campo>
            <Campo etiqueta="Categoría UICN" ayuda="CR y EN se publican como especie en peligro">
              {(p) => (
                <select {...p} value={datos.categoriaUicn} onChange={cambiar('categoriaUicn')}>
                  {CATEGORIAS_UICN.map(([v, t]) => <option key={v} value={v}>{t}</option>)}
                </select>
              )}
            </Campo>
          </div>
          <div className="campos" style={{ marginTop: 16 }}>
            <Campo etiqueta="Descripción">{(p) => <textarea {...p} value={datos.descripcion} onChange={cambiar('descripcion')} maxLength={5000} />}</Campo>
          </div>
        </div>

        <div className="tarjeta">
          <p className="tarjeta__titulo titulo-icono"><MapPin className="icono" aria-hidden="true" />Ubicación</p>
          <p className="texto-suave" style={{ margin: '0 0 16px' }}>El departamento es público; las coordenadas no.</p>
          <div className="campos">
            <Campo etiqueta="Departamento" obligatorio error={errores.departamento}>
              {(p) => (
                <select {...p} value={datos.departamento} onChange={cambiar('departamento')}>
                  <option value="">Elige uno</option>
                  {DEPARTAMENTOS.map((d) => <option key={d.codigo} value={d.nombre}>{d.nombre}</option>)}
                </select>
              )}
            </Campo>
            <Campo etiqueta="Latitud" error={errores.latitud} ayuda="Grados decimales, p. ej. 5.53214">
              {(p) => <input {...p} type="number" step="any" inputMode="decimal" value={datos.latitud} onChange={cambiar('latitud')} autoComplete="off" />}
            </Campo>
            <Campo etiqueta="Longitud" error={errores.longitud} ayuda="Grados decimales, p. ej. -73.36712">
              {(p) => <input {...p} type="number" step="any" inputMode="decimal" value={datos.longitud} onChange={cambiar('longitud')} autoComplete="off" />}
            </Campo>
          </div>
        </div>

        {plantilla && <CamposAtributos atributos={plantilla.atributos} valores={atributos} errores={errores}
          alCambiar={(clave, valor) => setAtributos((a) => ({ ...a, [clave]: valor }))} />}

        <div className="tarjeta">
          <p className="tarjeta__titulo titulo-icono"><Camera className="icono" aria-hidden="true" />Evidencias</p>
          <p className="texto-suave" style={{ margin: '0 0 16px' }}>
            Fotos JPEG o PNG y audios WAV, MP3, OGG o FLAC, hasta 9 MB cada uno. A las fotos y a los audios se les quitan los metadatos, donde el celular guarda la ubicación GPS.
          </p>
          <label className="boton boton--fantasma" style={{ cursor: 'pointer' }}>
            <Camera className="icono" aria-hidden="true" />Elegir archivos
            <input type="file" multiple accept={FORMATOS} className="solo-lectores" onChange={(e) => { agregarArchivos(e.target.files); e.target.value = ''; }} />
          </label>
          {archivos.length > 0 && (
            <ul className="evidencias" style={{ marginTop: 14 }}>
              {archivos.map((a, i) => (
                <li key={`${a.archivo.name}-${i}`} className="evidencia">
                  <Miniatura archivo={a.archivo} />
                  <div className="evidencia__pie">
                    <span title={a.archivo.name}>{a.archivo.name}</span>
                    <span className="texto-suave">{formatearTamano(a.archivo.size)}</span>
                    {a.error && <span className="campo__error">{a.error}</span>}
                    <button type="button" className="enlace" onClick={() => setArchivos((l) => l.filter((_, j) => j !== i))}
                      style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}><X className="icono" aria-hidden="true" />Quitar</button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="acciones">
          <button type="submit" className="boton" disabled={enviando}><Save className="icono" aria-hidden="true" />{enviando ? (progreso ?? 'Guardando…') : 'Registrar hallazgo'}</button>
          <button type="button" className="boton boton--fantasma" onClick={() => navegar(-1)} disabled={enviando}>Cancelar</button>
        </div>
        <datalist id={`${idListas}-especies`}>{(sugerencias?.especies ?? []).map((e) => <option key={e.nombreCientifico} value={e.nombreCientifico}>{e.nombreComun ?? ''}</option>)}</datalist>
        <datalist id={`${idListas}-familias`}>{(sugerencias?.familias ?? []).map((f) => <option key={f} value={f} />)}</datalist>
      </form>
    </section>
  );
}
