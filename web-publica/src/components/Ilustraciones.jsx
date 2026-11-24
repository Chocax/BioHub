import { useId } from 'react';

/*
 * Ilustraciones propias de BioHub (SVG, sin imágenes externas), compartidas en espíritu con la web interna
 * (web-interna/src/componentes/Ilustraciones.jsx): misma marca, mismo follaje pintado, misma mariposa morpho.
 * Son decorativas (aria-hidden). Las dos webs se despliegan por separado, por eso cada una tiene su copia.
 */

/** Monograma: una B dentro de un círculo con una hoja, como el sello de la galería. */
export function Monograma({ className = 'monograma', titulo }) {
  return (
    <svg className={className} viewBox="0 0 64 64" role={titulo ? 'img' : undefined} aria-hidden={titulo ? undefined : true}>
      {titulo && <title>{titulo}</title>}
      <circle cx="32" cy="32" r="29.5" fill="none" stroke="currentColor" strokeWidth="2.5" />
      <text x="31" y="43" textAnchor="middle" fontFamily="'Fraunces Variable', Georgia, serif" fontSize="34" fontWeight="500" fill="currentColor">B</text>
      <path d="M41 14c6 1 9 6 8 12-5-1-9-5-8-12z" fill="currentColor" />
    </svg>
  );
}

/** Arbusto pintado: manchas de verdes con borde roto y grano, en la base del héroe. */
export function Follaje({ className, invertir = false }) {
  const id = useId().replace(/:/g, '');
  const verdes = ['#3d4a1f', '#55602a', '#6b7431', '#7f863c', '#949845', '#a7a65a'];
  // Manchas fijas (no aleatorias): la ilustración es igual en cada render y en las pruebas.
  const manchas = [
    [70, 250, 95], [160, 215, 110], [265, 240, 100], [360, 205, 105], [450, 245, 90],
    [120, 160, 70], [230, 140, 78], [330, 150, 72], [420, 175, 64], [190, 95, 52], [300, 90, 48],
  ];
  return (
    <svg className={className} viewBox="0 0 520 300" aria-hidden="true" style={invertir ? { transform: 'scaleX(-1)' } : undefined}>
      <defs>
        <filter id={`pincel-${id}`} x="-10%" y="-10%" width="120%" height="120%">
          <feTurbulence type="fractalNoise" baseFrequency=".035" numOctaves="3" seed="7" result="ruido" />
          <feDisplacementMap in="SourceGraphic" in2="ruido" scale="34" xChannelSelector="R" yChannelSelector="G" result="roto" />
          <feTurbulence type="fractalNoise" baseFrequency=".75" numOctaves="2" seed="3" result="grano" />
          <feColorMatrix in="grano" type="matrix" values="0 0 0 0 .1  0 0 0 0 .12  0 0 0 0 .04  0 0 0 .55 0" result="sombra" />
          <feComposite in="sombra" in2="roto" operator="in" result="textura" />
          <feBlend in="textura" in2="roto" mode="multiply" />
        </filter>
      </defs>
      <g filter={`url(#pincel-${id})`}>
        {manchas.map(([cx, cy, r], i) => (
          <circle key={i} cx={cx} cy={cy} r={r} fill={verdes[(i * 7 + 3) % verdes.length]} />
        ))}
        {manchas.slice(5).map(([cx, cy, r], i) => (
          <circle key={`luz-${i}`} cx={cx - r * 0.25} cy={cy - r * 0.3} r={r * 0.45} fill="#b8b46a" opacity=".55" />
        ))}
        <rect x="0" y="270" width="520" height="40" fill="#3d4a1f" />
      </g>
    </svg>
  );
}

/** Mariposa (Morpho azul, típica de Colombia) que cruza el héroe. */
export function Mariposa({ className }) {
  return (
    <svg className={className} viewBox="0 0 64 48" aria-hidden="true">
      <g className="ala">
        <path d="M31 24C24 6 6 2 4 12c-2 9 9 13 15 13-7 3-11 12-4 16 6 3 13-6 16-17z" fill="#2f6fb3" />
        <path d="M33 24C40 6 58 2 60 12c2 9-9 13-15 13 7 3 11 12 4 16-6 3-13-6-16-17z" fill="#3d82c7" />
        <path d="M9 13c3-5 12-2 18 9M55 13c-3-5-12-2-18 9" stroke="#123b66" strokeWidth="1.2" fill="none" opacity=".6" />
      </g>
      <rect x="31" y="15" width="2.2" height="20" rx="1.1" fill="#1d1d1b" />
    </svg>
  );
}
