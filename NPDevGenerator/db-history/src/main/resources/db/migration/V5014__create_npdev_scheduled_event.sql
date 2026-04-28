CREATE TABLE IF NOT EXISTS npdev_scheduled_event (
    id UUID PRIMARY KEY,
    schedule_key VARCHAR(512) NOT NULL UNIQUE,
    orchestration_name VARCHAR(200) NOT NULL,
    action_index INTEGER NOT NULL,
    source_event_name VARCHAR(200) NOT NULL,
    source_event_id VARCHAR(200),
    trigger_correlation_id VARCHAR(200),
    event_name VARCHAR(200) NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS ix_npdev_scheduled_event_status_due
    ON npdev_scheduled_event(status, due_at);

CREATE INDEX IF NOT EXISTS ix_npdev_scheduled_event_source
    ON npdev_scheduled_event(source_event_name, source_event_id);
