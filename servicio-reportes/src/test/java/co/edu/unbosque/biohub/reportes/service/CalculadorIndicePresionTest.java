package co.edu.unbosque.biohub.reportes.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import co.edu.unbosque.biohub.reportes.domain.ConteoDepartamento;

/** RF15 y RF16: la fórmula del índice y la marca de alerta, caso por caso. */
class CalculadorIndicePresionTest {

    private static ConteoDepartamento c(String depto, long hallazgos, long enPeligro, long especies) {
        return new ConteoDepartamento(depto, hallazgos, enPeligro, especies);
    }

    @Test
    void presionAltaYCrecienteEsZonaDeAlerta() {
        // 8 de 10 en peligro (0,8) y antes 3 en 3 meses (promedio 1): crecimiento acotado a 1.
        IndicePresion i = CalculadorIndicePresion.calcular(List.of(c("Boyacá", 10, 8, 4)),
                List.of(c("Boyacá", 9, 3, 3))).get(0);

        assertThat(i.proporcionEnPeligro()).isEqualTo(0.8);
        assertThat(i.promedioEnPeligroPrevio()).isEqualTo(1.0);
        assertThat(i.crecimiento()).isEqualTo(1.0);
        assertThat(i.indice()).isEqualTo(86.0);
        assertThat(i.nivel()).isEqualTo("ALTO");
        assertThat(i.alerta()).isTrue();
    }

    @Test
    void presionAltaPeroQueNoCreceNoEsAlerta() {
        // 6 de 10 (0,6) y antes 6 al mes: crecimiento 0 → índice 42 (MEDIO), sin alerta.
        IndicePresion i = CalculadorIndicePresion.calcular(List.of(c("Meta", 10, 6, 5)),
                List.of(c("Meta", 30, 18, 6))).get(0);

        assertThat(i.crecimiento()).isZero();
        assertThat(i.indice()).isEqualTo(42.0);
        assertThat(i.nivel()).isEqualTo("MEDIO");
        assertThat(i.alerta()).isFalse();
    }

    @Test
    void sinEspeciesEnPeligroLaPresionEsBaja() {
        IndicePresion i = CalculadorIndicePresion.calcular(List.of(c("Huila", 20, 0, 9)), List.of()).get(0);

        assertThat(i.indice()).isZero();
        assertThat(i.nivel()).isEqualTo("BAJO");
        assertThat(i.alerta()).isFalse();
    }

    @Test
    void sinHistorialCualquierHallazgoEnPeligroCuentaComoCrecimiento() {
        IndicePresion i = CalculadorIndicePresion.calcular(List.of(c("Chocó", 4, 1, 2)), List.of()).get(0);

        assertThat(i.crecimiento()).isEqualTo(1.0);
        assertThat(i.indice()).isEqualTo(47.5);
    }

    @Test
    void ordenaDelMasPresionadoAlMenosYCruzaPorNombreSinImportarMayusculas() {
        List<IndicePresion> r = CalculadorIndicePresion.calcular(
                List.of(c("Huila", 10, 0, 3), c("BOYACÁ", 10, 9, 3)), List.of(c("boyacá", 3, 3, 1)));

        assertThat(r).extracting(IndicePresion::departamento).containsExactly("BOYACÁ", "Huila");
        assertThat(r.get(0).promedioEnPeligroPrevio()).isEqualTo(1.0);
    }
}
