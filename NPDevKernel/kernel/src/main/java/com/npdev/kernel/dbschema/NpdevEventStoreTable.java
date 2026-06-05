package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.INTEGER;
import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

public final class NpdevEventStoreTable {
    public static final String NAME = "npdev_event_store";

    private NpdevEventStoreTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("event_id", TEXT),
                        InternalColumnDefinition.required("event_name", TEXT),
                        InternalColumnDefinition.required("correlation_id", TEXT),
                        InternalColumnDefinition.required("causation_id", TEXT),
                        InternalColumnDefinition.required("flow_name", TEXT),
                        InternalColumnDefinition.required("step_index", INTEGER),
                        InternalColumnDefinition.required("timestamp_ms", BIGINT),
                        InternalColumnDefinition.required("payload_json", JSON_DOCUMENT),
                        InternalColumnDefinition.required("metadata_json", JSON_DOCUMENT),
                        InternalColumnDefinition.optional("tenant_id", TEXT),
                        InternalColumnDefinition.optional("actor_id", TEXT)
                ),
                InternalPrimaryKeyDefinition.of("event_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_event_store_event_name", "event_name"),
                        InternalIndexDefinition.index("idx_npdev_event_store_correlation_id", "correlation_id"),
                        InternalIndexDefinition.index("idx_npdev_event_store_event_correlation", "event_name", "correlation_id", "timestamp_ms", "event_id"),
                        InternalIndexDefinition.index("idx_npdev_event_store_tenant", "tenant_id"),
                        InternalIndexDefinition.index("idx_evt_tenant_corr_ts", "tenant_id", "correlation_id", "timestamp_ms"),
                        InternalIndexDefinition.index("idx_evt_tenant_name_ts", "tenant_id", "event_name", "timestamp_ms")
                )
        );
    }
}
