package com.finalexec.npdev.migration;

import java.util.Comparator;
import java.util.List;

public record StorageTableSchema(
        String name,
        List<StorageColumnSchema> columns
) {
    public StorageTableSchema normalized() {
        return new StorageTableSchema(
                name == null ? "" : name.trim(),
                (columns == null ? List.<StorageColumnSchema>of() : columns)
                        .stream()
                        .map(StorageColumnSchema::normalized)
                        .sorted(Comparator.comparing(StorageColumnSchema::name))
                        .toList()
        );
    }
}
