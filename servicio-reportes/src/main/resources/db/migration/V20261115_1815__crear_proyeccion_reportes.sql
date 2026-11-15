CREATE TABLE hallazgo_reporte (
                                hallazgo_id         BINARY(16)   NOT NULL,
                                version             INT          NOT NULL,
                                nombre_cientifico   VARCHAR(150) NOT NULL,
                                nombre_comun        VARCHAR(150) NULL,
                                familia             VARCHAR(100) NOT NULL,
                                tipo_especimen      VARCHAR(60)  NULL,
                                fecha_recoleccion   DATE         NOT NULL,
                                estado_conservacion VARCHAR(30)  NOT NULL,
                                departamento        VARCHAR(80)  NOT NULL,
                                region              VARCHAR(80)  NULL,
                                actualizado_en      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

                                CONSTRAINT pk_hallazgo_reporte PRIMARY KEY (hallazgo_id),
                                CONSTRAINT ck_hallazgo_reporte_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_hallazgo_reporte_fecha        ON hallazgo_reporte (fecha_recoleccion);
CREATE INDEX idx_hallazgo_reporte_departamento ON hallazgo_reporte (departamento, estado_conservacion);
CREATE INDEX idx_hallazgo_reporte_familia      ON hallazgo_reporte (familia);
CREATE INDEX idx_hallazgo_reporte_estado       ON hallazgo_reporte (estado_conservacion);

CREATE TABLE evento_procesado_reporte (
                                        evento_id      BINARY(16)  NOT NULL,
                                        tipo           VARCHAR(80) NOT NULL,
                                        procesado_en   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

                                        CONSTRAINT pk_evento_procesado_reporte PRIMARY KEY (evento_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
