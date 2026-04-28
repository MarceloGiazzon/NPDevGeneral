package com.npdev.generator.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MigrationDiffEngine {

    public MigrationPlan diff(StorageSchemaSnapshot previous, StorageSchemaSnapshot current) {
        StorageSchemaSnapshot prev = previous == null
                ? new StorageSchemaSnapshot("none", List.of())
                : previous.normalized();
        StorageSchemaSnapshot curr = current == null
                ? new StorageSchemaSnapshot("none", List.of())
                : current.normalized();

        Map<String, StorageTableSchema> prevTables = indexTables(prev.tables());
        Map<String, StorageTableSchema> currTables = indexTables(curr.tables());

        List<MigrationOperation> operations = new ArrayList<>();

        for (StorageTableSchema currTable : curr.tables()) {
            StorageTableSchema prevTable = prevTables.get(currTable.name());
            if (prevTable == null) {
                operations.add(MigrationOperation.createTable(currTable.name()));
                for (StorageColumnSchema column : currTable.columns()) {
                    operations.add(MigrationOperation.addColumn(currTable.name(), column.name(), column.sqlType()));
                    if (column.required()) {
                        operations.add(MigrationOperation.setNotNull(currTable.name(), column.name()));
                    }
                    if (column.unique()) {
                        operations.add(MigrationOperation.createUniqueIndex(
                                currTable.name(),
                                column.name(),
                                isCaseInsensitive(column)
                        ));
                    }
                }
                continue;
            }

            Map<String, StorageColumnSchema> prevColumns = indexColumns(prevTable.columns());
            for (StorageColumnSchema currColumn : currTable.columns()) {
                StorageColumnSchema prevColumn = prevColumns.get(currColumn.name());
                if (prevColumn == null) {
                    operations.add(MigrationOperation.addColumn(currTable.name(), currColumn.name(), currColumn.sqlType()));
                    if (currColumn.required()) {
                        operations.add(MigrationOperation.setNotNull(currTable.name(), currColumn.name()));
                    }
                    if (currColumn.unique()) {
                        operations.add(MigrationOperation.createUniqueIndex(
                                currTable.name(),
                                currColumn.name(),
                                isCaseInsensitive(currColumn)
                        ));
                    }
                    continue;
                }

                if (!prevColumn.required() && currColumn.required()) {
                    operations.add(MigrationOperation.setNotNull(currTable.name(), currColumn.name()));
                }
                if (!prevColumn.unique() && currColumn.unique()) {
                    operations.add(MigrationOperation.createUniqueIndex(
                            currTable.name(),
                            currColumn.name(),
                            isCaseInsensitive(currColumn)
                    ));
                }
            }
        }

        return new MigrationPlan(operations).normalized();
    }

    private static Map<String, StorageTableSchema> indexTables(List<StorageTableSchema> tables) {
        Map<String, StorageTableSchema> out = new LinkedHashMap<>();
        for (StorageTableSchema table : tables) {
            out.put(table.name(), table);
        }
        return out;
    }

    private static Map<String, StorageColumnSchema> indexColumns(List<StorageColumnSchema> columns) {
        Map<String, StorageColumnSchema> out = new LinkedHashMap<>();
        for (StorageColumnSchema column : columns) {
            out.put(column.name(), column);
        }
        return out;
    }

    private static boolean isCaseInsensitive(StorageColumnSchema column) {
        return "VARCHAR".equalsIgnoreCase(column.sqlType()) || "TEXT".equalsIgnoreCase(column.sqlType());
    }
}
