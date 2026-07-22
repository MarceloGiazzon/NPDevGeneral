package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.LARGE_TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TIMESTAMP;

import java.util.List;

public final class NpdevPublicationAuditTable {
    public static final String NAME = "npdev_publication_audit";

    private NpdevPublicationAuditTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("publication_audit_id", TEXT),
                        InternalColumnDefinition.optional("publication_execution_id", TEXT),
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("publication_reference", TEXT),
                        InternalColumnDefinition.required("audit_category", TEXT),
                        InternalColumnDefinition.required("audit_message", LARGE_TEXT),
                        InternalColumnDefinition.required("audit_payload", JSON_DOCUMENT),
                        InternalColumnDefinition.required("created_at", TIMESTAMP)
                ),
                InternalPrimaryKeyDefinition.of("publication_audit_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_publication_audit_execution_created", "publication_execution_id", "created_at"),
                        InternalIndexDefinition.index("idx_npdev_publication_audit_tenant_created", "tenant_id", "created_at")
                )
        );
    }
}
