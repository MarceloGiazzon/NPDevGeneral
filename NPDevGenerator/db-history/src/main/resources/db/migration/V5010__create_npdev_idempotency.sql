CREATE TABLE IF NOT EXISTS npdev_idempotency (
    tenant_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    status TEXT NOT NULL,
    result_json_redacted TEXT,
    error_code TEXT,
    PRIMARY KEY (tenant_id, capability, operation, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_npdev_idempotency_tenant
    ON npdev_idempotency(tenant_id);
