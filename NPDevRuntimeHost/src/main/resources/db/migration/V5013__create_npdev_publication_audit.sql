CREATE TABLE IF NOT EXISTS npdev_publication_audit (
    publication_audit_id UUID PRIMARY KEY,
    publication_execution_id UUID,
    tenant_id VARCHAR(200) NOT NULL,
    publication_reference VARCHAR(200) NOT NULL,
    audit_category VARCHAR(128) NOT NULL,
    audit_message TEXT NOT NULL,
    audit_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_npdev_publication_audit_execution
    ON npdev_publication_audit(publication_execution_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_npdev_publication_audit_tenant
    ON npdev_publication_audit(tenant_id, created_at DESC);
