CREATE TABLE equipo_trabajo (
                              id                  BINARY(16)   NOT NULL,
                              codigo_inventario   VARCHAR(40)  NOT NULL,
                              nombre              VARCHAR(120) NOT NULL,
                              categoria           VARCHAR(50)  NOT NULL,
                              descripcion         VARCHAR(500) NULL,
                              estado              VARCHAR(20)  NOT NULL,
                              custodio_id         VARCHAR(64)  NULL,
                              custodio_usuario    VARCHAR(100) NULL,
                              asignado_en         DATETIME(3)  NULL,
                              creado_en           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                              actualizado_en      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

                              CONSTRAINT pk_equipo_trabajo PRIMARY KEY (id),
                              CONSTRAINT uq_equipo_trabajo_codigo UNIQUE (codigo_inventario),
                              CONSTRAINT ck_equipo_trabajo_estado CHECK (estado IN ('DISPONIBLE', 'ASIGNADO', 'MANTENIMIENTO', 'BAJA')),
                              CONSTRAINT ck_equipo_trabajo_asignacion CHECK (
                                (estado = 'ASIGNADO' AND custodio_id IS NOT NULL AND custodio_usuario IS NOT NULL AND asignado_en IS NOT NULL)
                                  OR
                                (estado <> 'ASIGNADO' AND custodio_id IS NULL AND custodio_usuario IS NULL AND asignado_en IS NULL)
                                )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_equipo_trabajo_estado   ON equipo_trabajo (estado, nombre);
CREATE INDEX idx_equipo_trabajo_custodio ON equipo_trabajo (custodio_id);
