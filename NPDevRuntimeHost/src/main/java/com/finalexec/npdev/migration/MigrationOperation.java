package com.finalexec.npdev.migration;

public record MigrationOperation(
        String kind,
        String tableName,
        String columnName,
        String detail
) {
}
