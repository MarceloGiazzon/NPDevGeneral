CREATE TABLE IF NOT EXISTS npdev_correlation_owner (
    correlation_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_npdev_correlation_owner_tenant
    ON npdev_correlation_owner(tenant_id);
