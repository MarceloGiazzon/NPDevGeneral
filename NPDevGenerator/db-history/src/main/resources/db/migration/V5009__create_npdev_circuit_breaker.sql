CREATE TABLE IF NOT EXISTS npdev_circuit_breaker (
    tenant_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    operation TEXT NOT NULL,
    state TEXT NOT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    opened_at_ms BIGINT NOT NULL DEFAULT 0,
    last_failure_at_ms BIGINT NOT NULL DEFAULT 0,
    half_open_allowed_at_ms BIGINT NOT NULL DEFAULT 0,
    half_open_trial_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, capability, operation)
);

CREATE INDEX IF NOT EXISTS idx_npdev_circuit_breaker_tenant
    ON npdev_circuit_breaker(tenant_id);
