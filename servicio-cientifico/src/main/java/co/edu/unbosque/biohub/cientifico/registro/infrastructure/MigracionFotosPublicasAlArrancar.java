package co.edu.unbosque.biohub.cientifico.registro.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import co.edu.unbosque.biohub.cientifico.registro.service.RepublicadorFotosPublicas;

/**
 * Al arrancar, genera las miniaturas que falten de fotos que ya eran públicas antes de D66 y las publica
 * ({@code evidencia.publicada}). El trabajo corre en segundo plano y cualquier fallo (base o broker caídos) solo se
 * registra: el servicio arranca igual. Lo que no salga ahora se recupera con
 * {@code POST /api/cientifico/admin/fotos-publicas/republicar}.
 *
 * <p>Se apaga con {@code biohub.registro.fotos-publicas.migrar-al-arrancar=false}.
 */
@Component
public class MigracionFotosPublicasAlArrancar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigracionFotosPublicasAlArrancar.class);

    private final RepublicadorFotosPublicas republicador;
    private final boolean activa;

    public MigracionFotosPublicasAlArrancar(RepublicadorFotosPublicas republicador,
            @Value("${biohub.registro.fotos-publicas.migrar-al-arrancar:true}") boolean activa) {
        this.republicador = republicador;
        this.activa = activa;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!activa) {
            return;
        }
        try {
            int fotos = republicador.republicarSinMiniatura();
            if (fotos > 0) {
                log.info("Migración D66: {} fotos públicas sin miniatura; se generan y publican en segundo plano", fotos);
            }
        } catch (RuntimeException e) {
            log.error("No se pudo iniciar la migración de fotos públicas (D66): {}", e.getClass().getSimpleName());
        }
    }
}
