CREATE TABLE IF NOT EXISTS npdev_event_store (
    event_id TEXT PRIMARY KEY,
    event_name TEXT NOT NULL,
    correlation_id TEXT NOT NULL,
    causation_id TEXT NOT NULL,
    flow_name TEXT NOT NULL,
    step_index INTEGER NOT NULL,
    timestamp_ms BIGINT NOT NULL,
    payload_json TEXT NOT NULL,
    metadata_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_npdev_event_store_event_name
    ON npdev_event_store(event_name);

CREATE INDEX IF NOT EXISTS idx_npdev_event_store_correlation_id
    ON npdev_event_store(correlation_id);

CREATE INDEX IF NOT EXISTS idx_npdev_event_store_event_correlation
    ON npdev_event_store(event_name, correlation_id, timestamp_ms, event_id);
