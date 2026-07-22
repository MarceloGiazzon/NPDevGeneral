package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

public final class NpdevSchemaMetadataTable {
    public static final String NAME = "npdev_schema_metadata";

    private NpdevSchemaMetadataTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("metadata_key", TEXT),
                        InternalColumnDefinition.required("metadata_value", TEXT),
                        InternalColumnDefinition.required("updated_at_ms", BIGINT)
                ),
                InternalPrimaryKeyDefinition.of("metadata_key"),
                List.of()
        );
    }
}
