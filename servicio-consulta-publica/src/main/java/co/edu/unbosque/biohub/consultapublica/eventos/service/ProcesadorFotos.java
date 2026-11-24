package co.edu.unbosque.biohub.consultapublica.eventos.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import co.edu.unbosque.biohub.consultapublica.domain.FotoPublica;
import co.edu.unbosque.biohub.consultapublica.domain.ResultadoProyeccion;
import co.edu.unbosque.biohub.consultapublica.eventos.domain.EventoSobre;
import co.edu.unbosque.biohub.consultapublica.service.InvalidadorCacheFichas;
import co.edu.unbosque.biohub.consultapublica.service.ProyectorFotos;

/**
 * Procesa un evento {@code evidencia.publicada} o {@code evidencia.retirada} (D66, contrato 04 §7.6): valida los
 * datos (y la miniatura), los proyecta en una transacción y, solo si hubo cambio y YA con el commit hecho (este
 * método no es transaccional), vacía el catálogo de la caché. Los errores de validación son
 * {@link EventoInvalidoException} (DLQ, sin reintentos) y nunca incluyen valores del evento.
 */
@Service
public class ProcesadorFotos {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorFotos.class);

    static final String PUBLICADA = "evidencia.publicada";
    static final String RETIRADA = "evidencia.retirada";
    static final int LADO_MAXIMO = 1200;
    /** Un {@code cambioEn} más adelantado que esto congelaría la foto (nada posterior lo reemplazaría): se rechaza. */
    static final Duration ADELANTO_MAXIMO = Duration.ofDays(1);
    private static final Instant PRIMER_CAMBIO_PLAUSIBLE = Instant.parse("2020-01-01T00:00:00Z");
    private static final Pattern UUID_CANONICO =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final ProyectorFotos proyector;
    private final InvalidadorCacheFichas invalidador;
    private final Clock reloj;

    @Autowired
    public ProcesadorFotos(ProyectorFotos proyector, InvalidadorCacheFichas invalidador) {
        this(proyector, invalidador, Clock.systemUTC());
    }

    ProcesadorFotos(ProyectorFotos proyector, InvalidadorCacheFichas invalidador, Clock reloj) {
        this.proyector = proyector;
        this.invalidador = invalidador;
        this.reloj = reloj;
    }

    public ResultadoProyeccion procesar(EventoSobre evento) {
        Map<String, Object> datos = evento.datos();
        UUID evidenciaId = uuid(datos, "evidenciaId");
        UUID hallazgoId = uuid(datos, "hallazgoId");
        Instant cambioEn = instante(datos, "cambioEn");

        ResultadoProyeccion resultado;
        if (PUBLICADA.equals(evento.tipo())) {
            String sha256 = texto(datos, "sha256");
            if (!SHA256.matcher(sha256).matches()) {
                throw new EventoInvalidoException("Foto con sha256 inválido");
            }
            int ancho = lado(datos, "ancho");
            int alto = lado(datos, "alto");
            byte[] bytes = ValidadorFotoJpeg.validar(texto(datos, "jpegBase64"), sha256);
            resultado = proyector.publicar(new FotoPublica(evidenciaId, hallazgoId, sha256.toLowerCase(java.util.Locale.ROOT),
                    ancho, alto, bytes, cambioEn));
        } else if (RETIRADA.equals(evento.tipo())) {
            resultado = proyector.retirar(evidenciaId, hallazgoId, cambioEn);
        } else {
            throw new EventoInvalidoException("Evento de tipo desconocido");
        }

        if (resultado.huboCambio()) {
            invalidador.invalidarCatalogoPorFoto(evidenciaId);
            log.info("Foto {} eventoId={} tipo={} evidenciaId={} hallazgoId={}", resultado, evento.id(), evento.tipo(),
                    evidenciaId, hallazgoId);
        } else {
            log.debug("Evento de foto ignorado (repetido o anterior al guardado) eventoId={} evidenciaId={}",
                    evento.id(), evidenciaId);
        }
        return resultado;
    }

    private static String texto(Map<String, Object> datos, String campo) {
        if (datos.get(campo) instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw new EventoInvalidoException("Evento de foto sin " + campo + " o de tipo incorrecto");
    }

    private static UUID uuid(Map<String, Object> datos, String campo) {
        String s = texto(datos, campo);
        if (!UUID_CANONICO.matcher(s).matches()) {
            throw new EventoInvalidoException("Evento de foto con " + campo + " inválido");
        }
        return UUID.fromString(s);
    }

    private Instant instante(Map<String, Object> datos, String campo) {
        Instant instante;
        try {
            instante = Instant.parse(texto(datos, campo));
        } catch (java.time.format.DateTimeParseException e) {
            throw new EventoInvalidoException("Evento de foto con " + campo + " inválido");
        }
        if (instante.isBefore(PRIMER_CAMBIO_PLAUSIBLE) || instante.isAfter(reloj.instant().plus(ADELANTO_MAXIMO))) {
            throw new EventoInvalidoException("Evento de foto con " + campo + " fuera de rango");
        }
        return instante;
    }

    private static int lado(Map<String, Object> datos, String campo) {
        Object v = datos.get(campo);
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            long n = ((Number) v).longValue();
            if (n >= 1 && n <= LADO_MAXIMO) {
                return (int) n;
            }
        }
        throw new EventoInvalidoException("Evento de foto con " + campo + " fuera de 1.." + LADO_MAXIMO);
    }
}
