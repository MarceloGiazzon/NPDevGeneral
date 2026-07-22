package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TIMESTAMP;

import java.util.List;

public final class NpdevPublicationExecutionTable {
    public static final String NAME = "npdev_publication_execution";

    private NpdevPublicationExecutionTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("publication_execution_id", TEXT),
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("publication_reference", TEXT),
                        InternalColumnDefinition.optional("publication_transaction_id", TEXT),
                        InternalColumnDefinition.required("execution_mode", TEXT),
                        InternalColumnDefinition.required("publication_status", TEXT),
                        InternalColumnDefinition.optional("publication_outcome", TEXT),
                        InternalColumnDefinition.required("execution_payload", JSON_DOCUMENT),
                        InternalColumnDefinition.required("started_at", TIMESTAMP),
                        InternalColumnDefinition.optional("completed_at", TIMESTAMP),
                        InternalColumnDefinition.required("created_at", TIMESTAMP),
                        InternalColumnDefinition.required("updated_at", TIMESTAMP)
                ),
                InternalPrimaryKeyDefinition.of("publication_execution_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_publication_execution_tenant_status", "tenant_id", "publication_status"),
                        InternalIndexDefinition.index("idx_npdev_publication_execution_reference", "publication_reference")
                )
        );
    }
}
