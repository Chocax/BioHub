package co.edu.unbosque.biohub.reportes.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import co.edu.unbosque.biohub.reportes.domain.ConteoDepartamento;

/**
 * Calcula el índice de presión sobre los ecosistemas por departamento (RF15) y marca las zonas de alerta (RF16).
 *
 * <p><b>Modelo del prototipo</b> (el SRS acepta "una menor escala operativa" para HU-09). BioHub no tiene datos de
 * cobertura forestal, así que la presión se estima con lo que sí registra: qué tanto de lo que se encuentra en un
 * departamento son especies en peligro, y si eso está creciendo. Se cruzan los hallazgos del mes con los de los
 * tres meses anteriores:
 * <pre>
 *   proporción  = en peligro del mes / hallazgos del mes
 *   promedio    = en peligro de los 3 meses anteriores / 3
 *   crecimiento = (en peligro del mes − promedio) / promedio, acotado entre 0 y 1
 *                 (sin historial: 1 si hay hallazgos en peligro, 0 si no)
 *   índice      = 100 × (0,7 × proporción + 0,3 × crecimiento)
 *   nivel       = ALTO si índice ≥ 50, MEDIO si ≥ 25, BAJO si no
 *   alerta      = nivel ALTO y en peligro del mes &gt; promedio
 * </pre>
 * La proporción pesa más porque es lo que el mes muestra; el crecimiento distingue una presión nueva de una que
 * ya era habitual. Los pesos y umbrales están aquí, en un solo lugar, para ajustarlos con el Instituto.
 */
public final class CalculadorIndicePresion {

    static final double PESO_PROPORCION = 0.7;
    static final double PESO_CRECIMIENTO = 0.3;
    static final double UMBRAL_ALTO = 50;
    static final double UMBRAL_MEDIO = 25;
    static final int MESES_PREVIOS = 3;

    private CalculadorIndicePresion() {
    }

    /** Un índice por departamento con hallazgos en el mes, del más presionado al menos. */
    public static List<IndicePresion> calcular(List<ConteoDepartamento> mes, List<ConteoDepartamento> previos) {
        Map<String, ConteoDepartamento> anteriores = previos.stream()
                .collect(Collectors.toMap(c -> clave(c.departamento()), Function.identity(), (a, b) -> a));
        return mes.stream()
                .filter(c -> c.hallazgosOCero() > 0)
                .map(c -> indice(c, anteriores.get(clave(c.departamento()))))
                .sorted(Comparator.comparingDouble(IndicePresion::indice).reversed()
                        .thenComparing(IndicePresion::departamento))
                .toList();
    }

    static IndicePresion indice(ConteoDepartamento mes, ConteoDepartamento previo) {
        long hallazgos = mes.hallazgosOCero();
        long enPeligro = mes.enPeligroOCero();
        double proporcion = (double) enPeligro / hallazgos;
        double promedio = previo == null ? 0 : (double) previo.enPeligroOCero() / MESES_PREVIOS;
        double crecimiento;
        if (promedio == 0) {
            crecimiento = enPeligro > 0 ? 1 : 0;
        } else {
            crecimiento = Math.max(0, Math.min(1, (enPeligro - promedio) / promedio));
        }
        double indice = redondear(100 * (PESO_PROPORCION * proporcion + PESO_CRECIMIENTO * crecimiento));
        String nivel = indice >= UMBRAL_ALTO ? "ALTO" : indice >= UMBRAL_MEDIO ? "MEDIO" : "BAJO";
        boolean alerta = "ALTO".equals(nivel) && enPeligro > promedio;
        return new IndicePresion(mes.departamento(), hallazgos, enPeligro, mes.especiesOCero(),
                redondear(proporcion), redondear(promedio), redondear(crecimiento), indice, nivel, alerta);
    }

    private static String clave(String departamento) {
        return departamento == null ? "" : departamento.strip().toLowerCase();
    }

    private static double redondear(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}
