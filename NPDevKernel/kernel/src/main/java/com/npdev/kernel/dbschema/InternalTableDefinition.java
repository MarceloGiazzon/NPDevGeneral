package com.npdev.kernel.dbschema;

import java.util.List;

public record InternalTableDefinition(
        String name,
        List<InternalColumnDefinition> columns,
        InternalPrimaryKeyDefinition primaryKey,
        List<InternalIndexDefinition> indexes
) {
    public InternalTableDefinition {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        name = name.trim();
        columns = columns == null ? List.of() : List.copyOf(columns);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must be non-empty");
        }
        if (primaryKey == null) {
            throw new IllegalArgumentException("primaryKey must be non-null");
        }
    }
}
