CREATE TABLE IF NOT EXISTS npdev_audit_log (
    audit_id TEXT PRIMARY KEY,
    ts_ms BIGINT NOT NULL,
    tenant_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    roles TEXT NOT NULL,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    outcome TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    tags_json TEXT NOT NULL,
    meta_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_ts
    ON npdev_audit_log (tenant_id, ts_ms DESC);

CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_action
    ON npdev_audit_log (tenant_id, action);

CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_actor
    ON npdev_audit_log (tenant_id, actor_id);

CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_resource
    ON npdev_audit_log (tenant_id, resource_type, resource_id);

