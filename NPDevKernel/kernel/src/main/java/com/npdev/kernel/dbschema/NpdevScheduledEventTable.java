package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.INTEGER;
import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TIMESTAMP;

import java.util.List;

public final class NpdevScheduledEventTable {
    public static final String NAME = "npdev_scheduled_event";

    private NpdevScheduledEventTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("id", TEXT),
                        InternalColumnDefinition.required("schedule_key", TEXT),
                        InternalColumnDefinition.required("orchestration_name", TEXT),
                        InternalColumnDefinition.required("action_index", INTEGER),
                        InternalColumnDefinition.required("source_event_name", TEXT),
                        InternalColumnDefinition.optional("source_event_id", TEXT),
                        InternalColumnDefinition.optional("trigger_correlation_id", TEXT),
                        InternalColumnDefinition.required("event_name", TEXT),
                        InternalColumnDefinition.required("due_at", TIMESTAMP),
                        InternalColumnDefinition.required("payload", JSON_DOCUMENT),
                        InternalColumnDefinition.defaulted("status", TEXT, "'PENDING'"),
                        InternalColumnDefinition.defaulted("attempt_count", INTEGER, "0"),
                        InternalColumnDefinition.required("created_at", TIMESTAMP),
                        InternalColumnDefinition.required("updated_at", TIMESTAMP),
                        InternalColumnDefinition.optional("processed_at", TIMESTAMP)
                ),
                InternalPrimaryKeyDefinition.of("id"),
                List.of(
                        InternalIndexDefinition.unique("ux_npdev_scheduled_event_schedule_key", "schedule_key"),
                        InternalIndexDefinition.index("ix_npdev_scheduled_event_status_due", "status", "due_at"),
                        InternalIndexDefinition.index("ix_npdev_scheduled_event_source", "source_event_name", "source_event_id")
                )
        );
    }
}
