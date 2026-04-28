package com.npdev.generator.migration;

import java.util.Map;

public record MigrationOperation(
        Kind kind,
        String tableName,
        String columnName,
        String sqlType,
        Map<String, Object> attributes
) {
    public enum Kind {
        CREATE_TABLE,
        ADD_COLUMN,
        SET_NOT_NULL,
        CREATE_UNIQUE_INDEX
    }

    public MigrationOperation {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static MigrationOperation createTable(String tableName) {
        return new MigrationOperation(Kind.CREATE_TABLE, tableName, null, null, Map.of());
    }

    public static MigrationOperation addColumn(String tableName, String columnName, String sqlType) {
        return new MigrationOperation(Kind.ADD_COLUMN, tableName, columnName, sqlType, Map.of());
    }

    public static MigrationOperation setNotNull(String tableName, String columnName) {
        return new MigrationOperation(Kind.SET_NOT_NULL, tableName, columnName, null, Map.of());
    }

    public static MigrationOperation createUniqueIndex(String tableName, String columnName, boolean caseInsensitive) {
        return new MigrationOperation(
                Kind.CREATE_UNIQUE_INDEX,
                tableName,
                columnName,
                null,
                Map.of("caseInsensitive", caseInsensitive)
        );
    }
}
