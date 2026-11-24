package co.edu.unbosque.biohub.reportes.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class NormalizadorDepartamento {

    private static final Map<String, String> CODIGOS_DANE = Map.ofEntries(
        Map.entry("05", "Antioquia"),
        Map.entry("08", "Atlántico"),
        Map.entry("11", "Bogotá, D.C."),
        Map.entry("13", "Bolívar"),
        Map.entry("15", "Boyacá"),
        Map.entry("17", "Caldas"),
        Map.entry("18", "Caquetá"),
        Map.entry("19", "Cauca"),
        Map.entry("20", "Cesar"),
        Map.entry("23", "Córdoba"),
        Map.entry("25", "Cundinamarca"),
        Map.entry("27", "Chocó"),
        Map.entry("41", "Huila"),
        Map.entry("44", "La Guajira"),
        Map.entry("47", "Magdalena"),
        Map.entry("50", "Meta"),
        Map.entry("52", "Nariño"),
        Map.entry("54", "Norte de Santander"),
        Map.entry("63", "Quindío"),
        Map.entry("66", "Risaralda"),
        Map.entry("68", "Santander"),
        Map.entry("70", "Sucre"),
        Map.entry("73", "Tolima"),
        Map.entry("76", "Valle del Cauca"),
        Map.entry("81", "Arauca"),
        Map.entry("85", "Casanare"),
        Map.entry("86", "Putumayo"),
        Map.entry("88", "Archipiélago de San Andrés"),
        Map.entry("91", "Amazonas"),
        Map.entry("94", "Guainía"),
        Map.entry("95", "Guaviare"),
        Map.entry("97", "Vaupés"),
        Map.entry("99", "Vichada")
    );

    private static final Map<String, String> POR_CLAVE_PLANA = CODIGOS_DANE.values().stream()
        .collect(Collectors.toUnmodifiableMap(NormalizadorDepartamento::clavePlana, v -> v));

    private NormalizadorDepartamento() {
    }

    public static String normalizar(String entrada) {
        if (entrada == null || entrada.isBlank()) {
            return entrada;
        }
        String limpio = entrada.trim();
        if (limpio.matches("\\d{1,2}")) {
            String codigo = limpio.length() == 1 ? "0" + limpio : limpio;
            if (CODIGOS_DANE.containsKey(codigo)) {
                return CODIGOS_DANE.get(codigo);
            }
        }
        String clave = clavePlana(limpio);
        if (clave.startsWith("bogota")) {
            return "Bogotá, D.C.";
        }
        if (clave.contains("sanandres")) {
            return "Archipiélago de San Andrés";
        }
        return POR_CLAVE_PLANA.getOrDefault(clave, limpio);
    }

    private static String clavePlana(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "");
    }
}
