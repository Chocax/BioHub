/**
 * Qué significa cada marcador del mapa. Las formas son las mismas de los marcadores (clases ub-marcador--*):
 * la leyenda no depende del color, igual que el mapa.
 */
const ENTRADAS = [
  ['en-peligro', 'En peligro', 'Rombo rojo: especie en categoría UICN CR o EN.'],
  ['no-amenazada', 'No amenazada', 'Círculo verde: la especie no está amenazada.'],
  ['sin-evaluar', 'Sin evaluar', 'Cuadrado gris: sin categoría o con datos insuficientes.'],
  ['sin-evaluar ub-marcador--activo', 'Seleccionado', 'Con anillo azul: el hallazgo que estás viendo en el detalle.'],
];

export default function LeyendaMapa() {
  return (
    <section className="ub-leyenda" aria-label="Leyenda del mapa">
      <h2 className="ub-leyenda__titulo">Cómo leer el mapa</h2>
      <ul>
        {ENTRADAS.map(([clase, nombre, ayuda]) => (
          <li key={nombre}>
            <span className={`ub-marcador ub-marcador--${clase} ub-leyenda__marca`} aria-hidden="true"><span /></span>
            <span><strong>{nombre}</strong><span className="ub-leyenda__ayuda">{ayuda}</span></span>
          </li>
        ))}
      </ul>
    </section>
  );
}
