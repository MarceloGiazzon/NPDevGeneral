CREATE TABLE IF NOT EXISTS npdev_trace (
    execution_id TEXT PRIMARY KEY,
    correlation_id TEXT NOT NULL,
    flow_name TEXT NOT NULL,
    outcome TEXT NOT NULL,
    started_at_ms BIGINT NOT NULL,
    ended_at_ms BIGINT NOT NULL,
    trace_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_npdev_trace_corr
    ON npdev_trace(correlation_id);

CREATE INDEX IF NOT EXISTS idx_npdev_trace_flow
    ON npdev_trace(flow_name);

CREATE INDEX IF NOT EXISTS idx_npdev_trace_outcome
    ON npdev_trace(outcome);

CREATE INDEX IF NOT EXISTS idx_npdev_trace_started
    ON npdev_trace(started_at_ms);
