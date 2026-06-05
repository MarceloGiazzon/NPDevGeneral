package com.npdev.kernel.dbschema;

import java.util.List;

public record InternalIndexDefinition(
        String name,
        List<String> columns,
        boolean unique
) {
    public InternalIndexDefinition {
        name = require(name, "name");
        columns = columns == null ? List.of() : List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must be non-empty");
        }
    }

    public static InternalIndexDefinition index(String name, String... columns) {
        return new InternalIndexDefinition(name, List.of(columns), false);
    }

    public static InternalIndexDefinition unique(String name, String... columns) {
        return new InternalIndexDefinition(name, List.of(columns), true);
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.trim();
    }
}
