CREATE INDEX IF NOT EXISTS idx_trace_tenant_started
    ON npdev_trace (tenant_id, started_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_trace_tenant_flow_started
    ON npdev_trace (tenant_id, flow_name, started_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_trace_tenant_outcome_started
    ON npdev_trace (tenant_id, outcome, started_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_trace_tenant_corr
    ON npdev_trace (tenant_id, correlation_id);

CREATE INDEX IF NOT EXISTS idx_inst_tenant_updated
    ON npdev_flow_instance (tenant_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_inst_tenant_status_updated
    ON npdev_flow_instance (tenant_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_inst_tenant_corr
    ON npdev_flow_instance (tenant_id, correlation_id);

CREATE INDEX IF NOT EXISTS idx_evt_tenant_corr_ts
    ON npdev_event_store (tenant_id, correlation_id, timestamp_ms ASC);

CREATE INDEX IF NOT EXISTS idx_evt_tenant_name_ts
    ON npdev_event_store (tenant_id, event_name, timestamp_ms DESC);

