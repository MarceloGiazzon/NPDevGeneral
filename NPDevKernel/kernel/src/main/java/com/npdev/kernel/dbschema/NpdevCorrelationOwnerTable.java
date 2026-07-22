package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

public final class NpdevCorrelationOwnerTable {
    public static final String NAME = "npdev_correlation_owner";

    private NpdevCorrelationOwnerTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("correlation_id", TEXT),
                        InternalColumnDefinition.required("tenant_id", TEXT)
                ),
                InternalPrimaryKeyDefinition.of("correlation_id"),
                List.of(InternalIndexDefinition.index("idx_npdev_correlation_owner_tenant", "tenant_id"))
        );
    }
}
