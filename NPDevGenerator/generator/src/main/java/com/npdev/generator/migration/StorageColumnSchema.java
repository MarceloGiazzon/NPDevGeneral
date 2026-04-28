package com.npdev.generator.migration;

import java.util.Objects;

public record StorageColumnSchema(
        String name,
        String sqlType,
        boolean required,
        boolean unique
) {
    public StorageColumnSchema {
        name = requireNonBlank(name, "name");
        sqlType = requireNonBlank(sqlType, "sqlType");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }

    public StorageColumnSchema normalized() {
        return new StorageColumnSchema(
                name.trim().toLowerCase(),
                sqlType.trim().toUpperCase(),
                required,
                unique
        );
    }
}
