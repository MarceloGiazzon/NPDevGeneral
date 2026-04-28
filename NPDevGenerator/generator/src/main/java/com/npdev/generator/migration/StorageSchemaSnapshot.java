package com.npdev.generator.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record StorageSchemaSnapshot(
        String modelVersion,
        List<StorageTableSchema> tables
) {
    public StorageSchemaSnapshot {
        modelVersion = modelVersion == null ? "unknown" : modelVersion.trim();
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public StorageSchemaSnapshot normalized() {
        List<StorageTableSchema> normalizedTables = new ArrayList<>();
        for (StorageTableSchema table : tables) {
            if (table == null) {
                continue;
            }
            normalizedTables.add(table.normalized());
        }
        normalizedTables.sort(Comparator.comparing(StorageTableSchema::name));
        return new StorageSchemaSnapshot(modelVersion, normalizedTables);
    }
}
