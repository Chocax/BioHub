package co.edu.unbosque.biohub.reportes.service;

/**
 * Índice de presión sobre los ecosistemas de un departamento en un mes (RF15) y si es zona de alerta (RF16).
 *
 * @param departamento               departamento de los hallazgos
 * @param hallazgos                  hallazgos recolectados en el mes
 * @param enPeligro                  de ellos, de especies EN_PELIGRO (UICN CR o EN)
 * @param especies                   especies distintas registradas en el mes
 * @param proporcionEnPeligro        enPeligro / hallazgos, de 0 a 1
 * @param promedioEnPeligroPrevio    promedio mensual de hallazgos en peligro de los 3 meses anteriores
 * @param crecimiento                aumento de hallazgos en peligro frente a ese promedio, acotado de 0 a 1
 * @param indice                     0 a 100 (fórmula en {@link CalculadorIndicePresion})
 * @param nivel                      BAJO, MEDIO o ALTO
 * @param alerta                     nivel ALTO y más hallazgos en peligro que el promedio: zona de alerta
 */
public record IndicePresion(String departamento, long hallazgos, long enPeligro, long especies,
                            double proporcionEnPeligro, double promedioEnPeligroPrevio, double crecimiento,
                            double indice, String nivel, boolean alerta) {
}
