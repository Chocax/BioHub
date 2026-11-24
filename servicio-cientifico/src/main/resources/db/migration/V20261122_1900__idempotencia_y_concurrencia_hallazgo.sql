ALTER TABLE hallazgo
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN idempotency_key VARCHAR(100) NULL,
  ADD COLUMN idempotency_hash CHAR(64) NULL,
  ADD CONSTRAINT uq_hallazgo_idempotency_key UNIQUE (idempotency_key);
