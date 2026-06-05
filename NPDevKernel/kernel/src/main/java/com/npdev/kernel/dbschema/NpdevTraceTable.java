package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

public final class NpdevTraceTable {
    public static final String NAME = "npdev_trace";

    private NpdevTraceTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("execution_id", TEXT),
                        InternalColumnDefinition.required("correlation_id", TEXT),
                        InternalColumnDefinition.required("flow_name", TEXT),
                        InternalColumnDefinition.optional("tenant_id", TEXT),
                        InternalColumnDefinition.optional("actor_id", TEXT),
                        InternalColumnDefinition.required("outcome", TEXT),
                        InternalColumnDefinition.required("started_at_ms", BIGINT),
                        InternalColumnDefinition.required("ended_at_ms", BIGINT),
                        InternalColumnDefinition.required("trace_json", JSON_DOCUMENT)
                ),
                InternalPrimaryKeyDefinition.of("execution_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_trace_corr", "correlation_id"),
                        InternalIndexDefinition.index("idx_npdev_trace_flow", "flow_name"),
                        InternalIndexDefinition.index("idx_npdev_trace_outcome", "outcome"),
                        InternalIndexDefinition.index("idx_npdev_trace_started", "started_at_ms"),
                        InternalIndexDefinition.index("idx_npdev_trace_tenant", "tenant_id"),
                        InternalIndexDefinition.index("idx_trace_tenant_started", "tenant_id", "started_at_ms"),
                        InternalIndexDefinition.index("idx_trace_tenant_flow_started", "tenant_id", "flow_name", "started_at_ms"),
                        InternalIndexDefinition.index("idx_trace_tenant_outcome_started", "tenant_id", "outcome", "started_at_ms"),
                        InternalIndexDefinition.index("idx_trace_tenant_corr", "tenant_id", "correlation_id")
                )
        );
    }
}
