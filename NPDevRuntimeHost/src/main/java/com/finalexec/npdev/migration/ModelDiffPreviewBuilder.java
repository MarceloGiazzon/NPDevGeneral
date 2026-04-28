package com.finalexec.npdev.migration;

import java.util.ArrayList;
import java.util.List;

public final class ModelDiffPreviewBuilder {
    public ModelDiffPreview build(StorageSchemaSnapshot previous, StorageSchemaSnapshot current) {
        List<MigrationOperation> operations = MigrationSharedSupport.diff(previous, current);
        List<String> additive = new ArrayList<>();
        List<String> risky = new ArrayList<>();
        List<String> breaking = new ArrayList<>();

        for (MigrationOperation operation : operations) {
            switch (operation.kind()) {
                case "CREATE_TABLE", "ADD_COLUMN" -> additive.add(describe(operation));
                case "ALTER_COLUMN_TYPE", "SET_NOT_NULL" -> risky.add(describe(operation));
                case "DROP_TABLE", "DROP_COLUMN" -> breaking.add(describe(operation));
                default -> risky.add(describe(operation));
            }
        }

        return new ModelDiffPreview(
                true,
                previous == null ? "none" : previous.normalized().modelVersion(),
                current == null ? "unknown" : current.normalized().modelVersion(),
                MigrationSharedSupport.hash(previous),
                MigrationSharedSupport.hash(current),
                operations.size(),
                operations,
                additive,
                risky,
                breaking
        );
    }

    private static String describe(MigrationOperation operation) {
        String base = operation.kind() + " " + operation.tableName();
        if (operation.columnName() != null && !operation.columnName().isBlank()) {
            base += "." + operation.columnName();
        }
        if (operation.detail() != null && !operation.detail().isBlank()) {
            base += " (" + operation.detail() + ")";
        }
        return base;
    }
}
