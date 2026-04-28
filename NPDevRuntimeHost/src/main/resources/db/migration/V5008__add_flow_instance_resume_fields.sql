ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS resume_attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS last_resume_at TIMESTAMP;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS last_resume_error_code TEXT;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS next_eligible_resume_at TIMESTAMP;

ALTER TABLE npdev_flow_instance
    ADD COLUMN IF NOT EXISTS last_progress_at TIMESTAMP;

UPDATE npdev_flow_instance
SET last_progress_at = COALESCE(last_progress_at, updated_at)
WHERE last_progress_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_inst_tenant_next_eligible
    ON npdev_flow_instance (tenant_id, next_eligible_resume_at, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_inst_tenant_last_progress
    ON npdev_flow_instance (tenant_id, last_progress_at);