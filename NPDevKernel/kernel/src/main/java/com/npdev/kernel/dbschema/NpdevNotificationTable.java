package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.LARGE_TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TIMESTAMP;

import java.util.List;

/** PROC-4: persistent per-user in-app notifications (the shell bell). One row per recipient. */
public final class NpdevNotificationTable {
    public static final String NAME = "npdev_notification";

    private NpdevNotificationTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("notification_id", TEXT),
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("recipient", TEXT),
                        InternalColumnDefinition.required("title", TEXT),
                        InternalColumnDefinition.optional("body", LARGE_TEXT),
                        InternalColumnDefinition.optional("link", TEXT),
                        InternalColumnDefinition.optional("source", TEXT),
                        InternalColumnDefinition.required("created_at", TIMESTAMP),
                        InternalColumnDefinition.optional("read_at", TIMESTAMP)
                ),
                InternalPrimaryKeyDefinition.of("notification_id"),
                List.of(
                        InternalIndexDefinition.index("idx_notif_recipient_created", "tenant_id", "recipient", "created_at"),
                        InternalIndexDefinition.index("idx_notif_recipient_read", "tenant_id", "recipient", "read_at")
                )
        );
    }
}
