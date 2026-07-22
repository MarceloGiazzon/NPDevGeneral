package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

public final class NpdevIdempotencyTable {
    public static final String NAME = "npdev_idempotency";

    private NpdevIdempotencyTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("capability", TEXT),
                        InternalColumnDefinition.required("operation", TEXT),
                        InternalColumnDefinition.required("idempotency_key", TEXT),
                        InternalColumnDefinition.required("created_at_ms", BIGINT),
                        InternalColumnDefinition.required("status", TEXT),
                        InternalColumnDefinition.optional("result_json_redacted", JSON_DOCUMENT),
                        InternalColumnDefinition.optional("error_code", TEXT)
                ),
                InternalPrimaryKeyDefinition.of("tenant_id", "capability", "operation", "idempotency_key"),
                List.of(InternalIndexDefinition.index("idx_npdev_idempotency_tenant", "tenant_id"))
        );
    }
}
