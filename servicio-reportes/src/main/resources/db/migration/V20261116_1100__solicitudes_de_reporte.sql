-- HU-09 c.1 (SD-08): el reporte mensual se pide, se procesa en segundo plano y se descarga cuando esta listo.
-- Compatible con Aiven: PK en todas las tablas, sin triggers ni CREATE TABLE ... SELECT.

CREATE TABLE solicitud_reporte (
  id              BINARY(16)   NOT NULL,
  periodo         CHAR(7)      NOT NULL,   -- AAAA-MM: mes de recoleccion que cubre el reporte
  estado          VARCHAR(12)  NOT NULL,
  origen          VARCHAR(12)  NOT NULL,   -- MANUAL (lo pidio un analista) o PROGRAMADO (tarea mensual)
  solicitado_por  VARCHAR(100) NOT NULL,
  solicitado_en   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  iniciado_en     DATETIME(3)  NULL,
  terminado_en    DATETIME(3)  NULL,
  error           VARCHAR(300) NULL,
  resumen_json    JSON         NULL,       -- indices de presion por departamento (RF15, RF16)

  CONSTRAINT pk_solicitud_reporte PRIMARY KEY (id),
  CONSTRAINT ck_solicitud_reporte_estado CHECK (estado IN ('PENDIENTE','EN_PROCESO','LISTO','FALLIDO')),
  CONSTRAINT ck_solicitud_reporte_origen CHECK (origen IN ('MANUAL','PROGRAMADO')),
  CONSTRAINT ck_solicitud_reporte_periodo CHECK (periodo REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_solicitud_reporte_solicitado ON solicitud_reporte (solicitado_en);
CREATE INDEX idx_solicitud_reporte_periodo    ON solicitud_reporte (periodo, origen);

-- Los archivos van aparte para que listar solicitudes no cargue los PDF y CSV.
CREATE TABLE reporte_archivo (
  solicitud_id  BINARY(16)  NOT NULL,
  csv           MEDIUMBLOB  NOT NULL,
  pdf           MEDIUMBLOB  NOT NULL,

  CONSTRAINT pk_reporte_archivo PRIMARY KEY (solicitud_id),
  CONSTRAINT fk_reporte_archivo_solicitud FOREIGN KEY (solicitud_id)
    REFERENCES solicitud_reporte (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
