package co.edu.unbosque.biohub.cientifico.registro.service;

import java.util.Map;

/**
 * Un cambio de hallazgo que ya quedó confirmado en la base de datos y debe anunciarse al resto de
 * servicios. Lo publica {@link HallazgoService} y lo recibe, <b>después del commit</b>, el publicador
 * de infraestructura que arma el {@code EventoSobre} y lo envía a RabbitMQ.
 *
 * <p>Se publica después del commit, no dentro de la transacción, para no anunciar una versión que
 * finalmente no se guardó (04-contrato-api §7.5, obligación 1 del publicador).
 *
 * @param tipo    {@code hallazgo.registrado}, {@code hallazgo.reclasificado}, {@code hallazgo.actualizado},
 *                {@code evidencia.publicada} o {@code evidencia.retirada}
 * @param usuario {@code preferred_username} de quien hizo el cambio
 * @param datos   snapshot público del hallazgo (o datos de la foto), ya validado y <b>sin coordenadas</b>
 */
public record CambioHallazgoConfirmado(String tipo, String usuario, Map<String, Object> datos) {

    /** Alta de un hallazgo. */
    public static final String TIPO_REGISTRADO = "hallazgo.registrado";
    /** Cambio de estado de conservación o de taxonomía. */
    public static final String TIPO_RECLASIFICADO = "hallazgo.reclasificado";
    /** Una foto pasó a ser pública; {@code datos} lleva la miniatura en Base64 (04 §7.6, D66). */
    public static final String TIPO_FOTO_PUBLICADA = "evidencia.publicada";
    /** Una foto dejó de ser pública (04 §7.6, D66). */
    public static final String TIPO_FOTO_RETIRADA = "evidencia.retirada";
}
