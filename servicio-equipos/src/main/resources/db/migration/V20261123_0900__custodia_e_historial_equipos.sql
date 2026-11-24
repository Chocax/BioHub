ALTER TABLE equipo_trabajo
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER actualizado_en;

CREATE TABLE historial_custodia_equipo (
                                         id                BINARY(16)   NOT NULL,
                                         equipo_id         BINARY(16)   NOT NULL,
                                         accion            VARCHAR(30)  NOT NULL,
                                         estado_anterior   VARCHAR(20)  NULL,
                                         estado_nuevo      VARCHAR(20)  NOT NULL,
                                         custodio_id       VARCHAR(64)  NULL,
                                         custodio_usuario  VARCHAR(100) NULL,
                                         observacion       VARCHAR(300) NULL,
                                         registrado_en     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

                                         CONSTRAINT pk_historial_custodia PRIMARY KEY (id),
                                         CONSTRAINT fk_historial_equipo FOREIGN KEY (equipo_id)
                                           REFERENCES equipo_trabajo (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_historial_custodia_equipo_fecha
  ON historial_custodia_equipo (equipo_id, registrado_en DESC);
