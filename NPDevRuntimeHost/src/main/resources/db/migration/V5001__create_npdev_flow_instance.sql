CREATE TABLE IF NOT EXISTS npdev_flow_instance (
    execution_id TEXT PRIMARY KEY,
    flow_name TEXT NOT NULL,
    correlation_id TEXT NOT NULL,
    status TEXT NOT NULL,
    current_step_index INTEGER NOT NULL,
    waiting_for_event_name TEXT,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_correlation
    ON npdev_flow_instance(correlation_id);

CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_waiting_event
    ON npdev_flow_instance(waiting_for_event_name);

CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_status_updated
    ON npdev_flow_instance(status, updated_at, execution_id);
