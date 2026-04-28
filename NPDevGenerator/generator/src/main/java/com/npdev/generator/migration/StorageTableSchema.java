package com.npdev.generator.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record StorageTableSchema(
        String name,
        List<StorageColumnSchema> columns
) {
    public StorageTableSchema {
        name = requireNonBlank(name, "name");
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public StorageTableSchema normalized() {
        List<StorageColumnSchema> normalizedColumns = new ArrayList<>();
        for (StorageColumnSchema column : columns) {
            if (column == null) {
                continue;
            }
            normalizedColumns.add(column.normalized());
        }
        normalizedColumns.sort(Comparator.comparing(StorageColumnSchema::name));
        return new StorageTableSchema(name.trim().toLowerCase(), normalizedColumns);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
