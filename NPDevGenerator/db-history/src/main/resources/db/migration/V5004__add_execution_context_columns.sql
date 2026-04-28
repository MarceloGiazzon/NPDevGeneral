ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS tenant_id TEXT;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS actor_id TEXT;

CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_tenant
    ON npdev_flow_instance(tenant_id);

ALTER TABLE npdev_event_store
    ADD COLUMN IF NOT EXISTS tenant_id TEXT;

ALTER TABLE npdev_event_store
    ADD COLUMN IF NOT EXISTS actor_id TEXT;

CREATE INDEX IF NOT EXISTS idx_npdev_event_store_tenant
    ON npdev_event_store(tenant_id);

ALTER TABLE npdev_trace
    ADD COLUMN IF NOT EXISTS tenant_id TEXT;

ALTER TABLE npdev_trace
    ADD COLUMN IF NOT EXISTS actor_id TEXT;

CREATE INDEX IF NOT EXISTS idx_npdev_trace_tenant
    ON npdev_trace(tenant_id);
