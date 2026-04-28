ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS last_error_kind TEXT;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS last_error_code TEXT;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS last_error_message TEXT;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_inst_tenant_status_failed_at
    ON npdev_flow_instance (tenant_id, status, failed_at);

CREATE INDEX IF NOT EXISTS idx_inst_tenant_last_error_code
    ON npdev_flow_instance (tenant_id, last_error_code);
