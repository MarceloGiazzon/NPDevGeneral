package com.npdev.kernel.dbschema;

import java.util.List;

public record InternalPrimaryKeyDefinition(List<String> columns) {
    public InternalPrimaryKeyDefinition {
        columns = columns == null ? List.of() : List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("primary key columns must be non-empty");
        }
    }

    public static InternalPrimaryKeyDefinition of(String... columns) {
        return new InternalPrimaryKeyDefinition(List.of(columns));
    }
}
