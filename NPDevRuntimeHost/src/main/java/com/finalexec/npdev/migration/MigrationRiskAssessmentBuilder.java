package com.finalexec.npdev.migration;

import java.util.ArrayList;
import java.util.List;

public final class MigrationRiskAssessmentBuilder {
    public MigrationRiskAssessment build(StorageSchemaSnapshot previous, StorageSchemaSnapshot current) {
        List<MigrationOperation> operations = MigrationSharedSupport.diff(previous, current);
        List<String> safe = new ArrayList<>();
        List<String> backfill = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        List<String> breaking = new ArrayList<>();

        for (MigrationOperation operation : operations) {
            switch (operation.kind()) {
                case "CREATE_TABLE", "ADD_COLUMN" -> safe.add(operation.kind() + " " + operation.tableName());
                case "SET_NOT_NULL" -> backfill.add(operation.kind() + " " + operation.tableName() + "." + operation.columnName());
                case "ALTER_COLUMN_TYPE" -> manual.add(operation.kind() + " " + operation.tableName() + "." + operation.columnName());
                case "DROP_TABLE", "DROP_COLUMN" -> breaking.add(operation.kind() + " " + operation.tableName());
                default -> manual.add(operation.kind() + " " + operation.tableName());
            }
        }

        String overallRisk = !breaking.isEmpty() ? "BREAKING"
                : !manual.isEmpty() ? "MANUAL_REVIEW"
                : !backfill.isEmpty() ? "BACKFILL_REQUIRED"
                : "SAFE_ADDITIVE";

        return new MigrationRiskAssessment(overallRisk, safe, backfill, manual, breaking);
    }
}
