package com.npdev.generator.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ModelDiffPreviewBuilder {

    public ModelDiffPreview build(StorageSchemaSnapshot previous, StorageSchemaSnapshot current) {
        StorageSchemaSnapshot prev = previous == null
                ? new StorageSchemaSnapshot("none", List.of())
                : previous.normalized();
        StorageSchemaSnapshot curr = current == null
                ? new StorageSchemaSnapshot("unknown", List.of())
                : current.normalized();

        MigrationPlan firstPlan = new MigrationDiffEngine().diff(prev, curr).normalized();
        MigrationPlan secondPlan = new MigrationDiffEngine().diff(prev, curr).normalized();
        boolean deterministic = firstPlan.operations().equals(secondPlan.operations());

        Map<String, StorageTableSchema> prevTables = indexTables(prev.tables());
        Map<String, StorageTableSchema> currTables = indexTables(curr.tables());

        List<String> additiveChanges = new ArrayList<>();
        List<String> riskyChanges = new ArrayList<>();
        List<String> breakingChanges = new ArrayList<>();

        for (MigrationOperation operation : firstPlan.operations()) {
            if (operation == null) {
                continue;
            }
            switch (operation.kind()) {
                case CREATE_TABLE -> additiveChanges.add("create table " + operation.tableName());
                case ADD_COLUMN -> additiveChanges.add("add column " + operation.tableName() + "." + operation.columnName());
                case CREATE_UNIQUE_INDEX -> additiveChanges.add("add unique index " + operation.tableName() + "." + operation.columnName());
                case SET_NOT_NULL -> {
                    if (prevTables.containsKey(operation.tableName())) {
                        riskyChanges.add("tighten required " + operation.tableName() + "." + operation.columnName());
                    } else {
                        additiveChanges.add("set required on new table " + operation.tableName() + "." + operation.columnName());
                    }
                }
            }
        }

        for (StorageTableSchema prevTable : prev.tables()) {
            StorageTableSchema currTable = currTables.get(prevTable.name());
            if (currTable == null) {
                breakingChanges.add("remove table " + prevTable.name());
                continue;
            }

            Map<String, StorageColumnSchema> prevColumns = indexColumns(prevTable.columns());
            Map<String, StorageColumnSchema> currColumns = indexColumns(currTable.columns());

            for (StorageColumnSchema prevColumn : prevTable.columns()) {
                StorageColumnSchema currColumn = currColumns.get(prevColumn.name());
                if (currColumn == null) {
                    breakingChanges.add("remove column " + prevTable.name() + "." + prevColumn.name());
                    continue;
                }
                if (!Objects.equals(prevColumn.sqlType(), currColumn.sqlType())) {
                    riskyChanges.add(
                            "change type " + prevTable.name() + "." + prevColumn.name() +
                                    " from " + prevColumn.sqlType() + " to " + currColumn.sqlType()
                    );
                }
                if (prevColumn.required() && !currColumn.required()) {
                    additiveChanges.add("relax required " + prevTable.name() + "." + prevColumn.name());
                }
            }

            for (StorageColumnSchema currColumn : currTable.columns()) {
                if (!prevColumns.containsKey(currColumn.name())) {
                    additiveChanges.add("discover column " + currTable.name() + "." + currColumn.name());
                }
            }
        }

        for (StorageTableSchema currTable : curr.tables()) {
            if (!prevTables.containsKey(currTable.name())) {
                additiveChanges.add("discover table " + currTable.name());
            }
        }

        try {
            StorageSchemaSnapshotStore store = new StorageSchemaSnapshotStore();
            return new ModelDiffPreview(
                    deterministic,
                    prev.modelVersion(),
                    curr.modelVersion(),
                    store.computeCanonicalHash(prev),
                    store.computeCanonicalHash(curr),
                    firstPlan.operations().size(),
                    firstPlan.operations(),
                    additiveChanges,
                    riskyChanges,
                    breakingChanges
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build deterministic model diff preview", exception);
        }
    }

    private static Map<String, StorageTableSchema> indexTables(List<StorageTableSchema> tables) {
        Map<String, StorageTableSchema> out = new LinkedHashMap<>();
        for (StorageTableSchema table : tables) {
            if (table != null) {
                out.put(table.name(), table);
            }
        }
        return out;
    }

    private static Map<String, StorageColumnSchema> indexColumns(List<StorageColumnSchema> columns) {
        Map<String, StorageColumnSchema> out = new LinkedHashMap<>();
        for (StorageColumnSchema column : columns) {
            if (column != null) {
                out.put(column.name(), column);
            }
        }
        return out;
    }
}
