package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

public final class NpdevAuditLogTable {
    public static final String NAME = "npdev_audit_log";

    private NpdevAuditLogTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("audit_id", TEXT),
                        InternalColumnDefinition.required("ts_ms", BIGINT),
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("actor_id", TEXT),
                        InternalColumnDefinition.required("roles", TEXT),
                        InternalColumnDefinition.required("action", TEXT),
                        InternalColumnDefinition.required("resource_type", TEXT),
                        InternalColumnDefinition.required("resource_id", TEXT),
                        InternalColumnDefinition.required("outcome", TEXT),
                        InternalColumnDefinition.required("reason_code", TEXT),
                        InternalColumnDefinition.required("tags_json", JSON_DOCUMENT),
                        InternalColumnDefinition.required("meta_json", JSON_DOCUMENT)
                ),
                InternalPrimaryKeyDefinition.of("audit_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_audit_tenant_ts", "tenant_id", "ts_ms"),
                        InternalIndexDefinition.index("idx_npdev_audit_tenant_action", "tenant_id", "action"),
                        InternalIndexDefinition.index("idx_npdev_audit_tenant_actor", "tenant_id", "actor_id"),
                        InternalIndexDefinition.index("idx_npdev_audit_tenant_resource", "tenant_id", "resource_type", "resource_id")
                )
        );
    }
}
