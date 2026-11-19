package co.edu.unbosque.biohub.cientifico.registro.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import co.edu.unbosque.biohub.cientifico.registro.domain.CatalogoEspecies;

/** Galería de especies y listas desplegables de la web interna (D65). Valida y pagina; la lectura es del dominio. */
@Service
public class CatalogoEspeciesService {

    static final int TAMANO_POR_DEFECTO = 12;
    static final int TAMANO_MAXIMO = 48;
    static final int TOPE_SUGERENCIAS = 1000;

    private final CatalogoEspecies catalogo;

    public CatalogoEspeciesService(CatalogoEspecies catalogo) {
        this.catalogo = catalogo;
    }

    public record Pagina(List<CatalogoEspecies.Especie> content, int page, int size, long total, int totalPaginas) {
    }

    @Transactional(readOnly = true)
    public Pagina buscar(String textoCrudo, String familiaCruda, Integer pageCrudo, Integer sizeCrudo) {
        String texto = recortar(textoCrudo);
        String familia = recortar(familiaCruda);
        if (texto != null && texto.length() > 100) {
            throw new DatosHallazgoInvalidosException("q", "Máximo 100 caracteres.");
        }
        if (familia != null && familia.length() > 100) {
            throw new DatosHallazgoInvalidosException("familia", "Máximo 100 caracteres.");
        }
        int page = pageCrudo == null ? 0 : pageCrudo;
        int size = sizeCrudo == null ? TAMANO_POR_DEFECTO : sizeCrudo;
        if (page < 0) {
            throw new DatosHallazgoInvalidosException("page", "Debe ser 0 o mayor.");
        }
        if (size < 1 || size > TAMANO_MAXIMO) {
            throw new DatosHallazgoInvalidosException("size", "Debe estar entre 1 y " + TAMANO_MAXIMO + ".");
        }
        long total = catalogo.contar(texto, familia);
        List<CatalogoEspecies.Especie> contenido = total > (long) page * size
                ? catalogo.buscar(texto, familia, page, size) : List.of();
        return new Pagina(contenido, page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public CatalogoEspecies.Sugerencias sugerencias() {
        return catalogo.sugerencias(TOPE_SUGERENCIAS);
    }

    private static String recortar(String valor) {
        if (valor == null) {
            return null;
        }
        String t = valor.strip();
        return t.isEmpty() ? null : t;
    }
}
