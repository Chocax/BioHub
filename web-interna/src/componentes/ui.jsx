import { useEffect, useId, useRef } from 'react';
import { Link } from 'react-router';
import { Frailejon, Hex } from './Ilustraciones.jsx';

/** Pone el título de la pestaña y lleva el foco al encabezado de la página (lectores de pantalla). */
export function useTitulo(titulo) {
  const ref = useRef(null);
  useEffect(() => {
    document.title = `${titulo} · BioHub`;
    ref.current?.focus();
  }, [titulo]);
  return ref;
}

/** Agrega la clase "visible" cuando el elemento entra en pantalla (animación de aparición). */
export function useRevelar() {
  const ref = useRef(null);
  useEffect(() => {
    const nodo = ref.current;
    if (!nodo) return undefined;
    if (typeof IntersectionObserver === 'undefined') { nodo.classList.add('visible'); return undefined; }
    const observador = new IntersectionObserver((entradas) => {
      entradas.forEach((e) => { if (e.isIntersecting) { e.target.classList.add('visible'); observador.unobserve(e.target); } });
    }, { threshold: 0.12 });
    nodo.querySelectorAll('.revelar').forEach((n) => observador.observe(n));
    if (nodo.classList.contains('revelar')) observador.observe(nodo);
    return () => observador.disconnect();
  }, []);
  return ref;
}

export function CabeceraPagina({ eyebrow, titulo, pestana, descripcion, children }) {
  const ref = useTitulo(pestana ?? (typeof titulo === 'string' ? titulo : eyebrow));
  return (
    <header className="pagina__cabecera">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1 className="titulo-pagina" tabIndex={-1} ref={ref}>{titulo}</h1>
        {descripcion && <p className="texto-suave">{descripcion}</p>}
      </div>
      {children && <div className="acciones">{children}</div>}
    </header>
  );
}

export function Cargando({ texto = 'Cargando…' }) {
  return (
    <p className="cargando" role="status"><Hex className="cargando__hex" /> {texto}</p>
  );
}

export function Vacio({ titulo, children }) {
  return (
    <div className="vacio">
      <Frailejon />
      <p className="vacio__titulo">{titulo}</p>
      {children && <div>{children}</div>}
    </div>
  );
}

/** Muestra un error de la API (o cualquier Error) de forma legible. */
export function AvisoError({ error, titulo }) {
  const ref = useRef(null);
  // Lleva el aviso a la vista: en formularios largos el botón queda abajo y el error arriba.
  useEffect(() => { if (error) ref.current?.scrollIntoView?.({ block: 'center', behavior: 'smooth' }); }, [error]);
  if (!error) return null;
  const errores = error.errores ?? [];
  return (
    <div className="aviso aviso--error" role="alert" ref={ref}>
      <div>
        <p><strong>{titulo ?? 'No se pudo completar'}</strong> · {error.message}</p>
        {error.reintentarEn && <p>Intenta de nuevo en {error.reintentarEn} s.</p>}
        {errores.length > 0 && (
          <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
            {errores.map((e, i) => <li key={i}>{e.campo ? <strong>{e.campo}: </strong> : null}{e.mensaje}</li>)}
          </ul>
        )}
      </div>
    </div>
  );
}

export function Aviso({ tipo = 'info', children }) {
  return <div className={`aviso aviso--${tipo}`} role={tipo === 'error' ? 'alert' : 'status'}><div>{children}</div></div>;
}

const ESTADOS = {
  EN_PELIGRO: ['terracota', 'En peligro'],
  NO_AMENAZADA: ['musgo', 'No amenazada'],
  SIN_EVALUAR: ['neutro', 'Sin evaluar'],
  DISPONIBLE: ['musgo', 'Disponible'],
  ASIGNADO: ['pizarra', 'En expedición'],
  MANTENIMIENTO: ['ocre', 'Mantenimiento'],
  BAJA: ['neutro', 'De baja'],
  PENDIENTE: ['neutro', 'Pendiente'],
  EN_PROCESO: ['ocre', 'En proceso'],
  LISTO: ['musgo', 'Listo'],
  FALLIDO: ['terracota', 'Falló'],
  ACTIVA: ['musgo', 'Activa'],
  REVOCADA: ['terracota', 'Revocada'],
  ALTO: ['terracota', 'Alto'],
  MEDIO: ['ocre', 'Medio'],
  BAJO: ['musgo', 'Bajo'],
};

export function ChipEstado({ estado }) {
  const [color, texto] = ESTADOS[estado] ?? ['neutro', estado ?? '—'];
  return <span className={`chip chip--${color}`}>{texto}</span>;
}

/** Campo de formulario con etiqueta, ayuda y error asociados por aria. */
export function Campo({ etiqueta, obligatorio, ayuda, error, children, id: idDado }) {
  const generado = useId();
  const id = idDado ?? generado;
  const ayudaId = ayuda ? `${id}-ayuda` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const hijo = typeof children === 'function'
    ? children({ id, 'aria-describedby': [ayudaId, errorId].filter(Boolean).join(' ') || undefined, 'aria-invalid': error ? true : undefined })
    : children;
  return (
    <div className="campo">
      <label htmlFor={id}>{etiqueta}{obligatorio && <span className="campo__obligatorio" aria-hidden="true"> *</span>}</label>
      {hijo}
      {ayuda && <p className="campo__ayuda" id={ayudaId}>{ayuda}</p>}
      {error && <p className="campo__error" id={errorId}>{error}</p>}
    </div>
  );
}

export function Paginacion({ pagina, totalPaginas, total, alCambiar }) {
  if (!totalPaginas || totalPaginas <= 1) return total != null ? <p className="paginacion">{total} resultado{total === 1 ? '' : 's'}</p> : null;
  return (
    <nav className="paginacion" aria-label="Paginación">
      <span>{total} resultados · página {pagina + 1} de {totalPaginas}</span>
      <span className="acciones">
        <button type="button" className="boton boton--fantasma boton--chico" disabled={pagina === 0} onClick={() => alCambiar(pagina - 1)}>Anterior</button>
        <button type="button" className="boton boton--fantasma boton--chico" disabled={pagina + 1 >= totalPaginas} onClick={() => alCambiar(pagina + 1)}>Siguiente</button>
      </span>
    </nav>
  );
}

/** Pantalla para rutas que el rol no puede usar: explica por qué, sin llamar a la API. */
export function SinPermiso() {
  const ref = useTitulo('Sin permiso');
  return (
    <section className="pagina">
      <div className="tarjeta tarjeta--tinta muesca" style={{ maxWidth: 560 }}>
        <p className="eyebrow">Acceso restringido</p>
        <h1 className="titulo-pagina" tabIndex={-1} ref={ref} style={{ color: 'var(--paper)' }}>Esta sala no es para tu rol</h1>
        <p className="texto-suave" style={{ margin: '16px 0 24px' }}>
          Cada rol ve solo las funciones que le corresponden. Si necesitas este acceso, pídeselo a un administrador.
        </p>
        <Link className="boton boton--claro" to="/">Volver al inicio</Link>
      </div>
    </section>
  );
}

export function formatearFecha(valor, conHora = false) {
  if (!valor) return '—';
  const fecha = new Date(valor.length === 10 ? `${valor}T12:00:00` : valor);
  if (Number.isNaN(fecha.getTime())) return valor;
  return fecha.toLocaleString('es-CO', conHora
    ? { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Bogota' }
    : { dateStyle: 'medium', timeZone: 'America/Bogota' });
}

export function formatearTamano(bytes) {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
