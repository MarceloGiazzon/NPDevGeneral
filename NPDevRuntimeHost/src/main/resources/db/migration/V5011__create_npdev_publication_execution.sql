CREATE TABLE IF NOT EXISTS npdev_publication_execution (
    publication_execution_id UUID PRIMARY KEY,
    tenant_id VARCHAR(200) NOT NULL,
    publication_reference VARCHAR(200) NOT NULL,
    publication_transaction_id UUID,
    execution_mode VARCHAR(64) NOT NULL,
    publication_status VARCHAR(64) NOT NULL,
    publication_outcome VARCHAR(128),
    execution_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_npdev_publication_execution_tenant
    ON npdev_publication_execution(tenant_id, publication_status);

CREATE INDEX IF NOT EXISTS ix_npdev_publication_execution_reference
    ON npdev_publication_execution(publication_reference);
