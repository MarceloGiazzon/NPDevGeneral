package com.npdev.generator.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record MigrationPlan(List<MigrationOperation> operations) {
    public MigrationPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public MigrationPlan normalized() {
        List<MigrationOperation> list = new ArrayList<>(operations);
        list.sort(Comparator
                .comparingInt((MigrationOperation op) -> kindOrder(op.kind()))
                .thenComparing(op -> op.tableName() == null ? "" : op.tableName())
                .thenComparing(op -> op.columnName() == null ? "" : op.columnName())
                .thenComparing(op -> op.sqlType() == null ? "" : op.sqlType()));
        return new MigrationPlan(list);
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    private static int kindOrder(MigrationOperation.Kind kind) {
        if (kind == null) {
            return Integer.MAX_VALUE;
        }
        return switch (kind) {
            case CREATE_TABLE -> 10;
            case ADD_COLUMN -> 20;
            case SET_NOT_NULL -> 30;
            case CREATE_UNIQUE_INDEX -> 40;
        };
    }
}