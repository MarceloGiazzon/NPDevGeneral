package com.finalexec.npdev.migration;

import java.util.Comparator;
import java.util.List;

public record StorageSchemaSnapshot(
        String modelVersion,
        List<StorageTableSchema> tables
) {
    public StorageSchemaSnapshot normalized() {
        return new StorageSchemaSnapshot(
                modelVersion == null || modelVersion.isBlank() ? "unknown" : modelVersion.trim(),
                (tables == null ? List.<StorageTableSchema>of() : tables)
                        .stream()
                        .map(StorageTableSchema::normalized)
                        .sorted(Comparator.comparing(StorageTableSchema::name))
                        .toList()
        );
    }
}
