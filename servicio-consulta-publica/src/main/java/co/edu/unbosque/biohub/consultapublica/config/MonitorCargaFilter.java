package co.edu.unbosque.biohub.consultapublica.config;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import co.edu.unbosque.biohub.consultapublica.service.MonitorCarga;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Alimenta al {@link MonitorCarga} con cada lectura pública: las fichas y la portada de especies
 * ({@code /api/publica/fichas...} y {@code /api/publica/especies...}, con el catálogo, las familias y las fotos;
 * ARQ-R-5). Cuenta las peticiones en curso y mide su duración total (incluida la seguridad y la serialización).
 * No mide {@code ping} ni {@code eventos/recibidos} (diagnóstico, no es tráfico de visitantes). No toca la respuesta.
 */
public class MonitorCargaFilter extends OncePerRequestFilter {

    static final String[] PREFIJOS = {"/api/publica/fichas", "/api/publica/especies"};

    private final MonitorCarga monitor;

    public MonitorCargaFilter(MonitorCarga monitor) {
        this.monitor = monitor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ruta = request.getRequestURI();
        for (String prefijo : PREFIJOS) {
            if (ruta.equals(prefijo) || ruta.startsWith(prefijo + "/")) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long inicio = System.nanoTime();
        monitor.iniciarPeticion();
        try {
            chain.doFilter(request, response);
        } finally {
            monitor.terminarPeticion((System.nanoTime() - inicio) / 1_000_000);
        }
    }
}
