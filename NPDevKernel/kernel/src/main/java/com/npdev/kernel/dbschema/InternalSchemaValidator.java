package com.npdev.kernel.dbschema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InternalSchemaValidator {
    private InternalSchemaValidator() {
    }

    public static InternalSchemaValidationResult validate(List<InternalTableDefinition> tables) {
        List<String> errors = new ArrayList<>();
        List<InternalTableDefinition> safeTables = tables == null ? List.of() : tables;
        if (safeTables.isEmpty()) {
            errors.add("Internal table registry must not be empty.");
            return new InternalSchemaValidationResult(errors);
        }

        Set<String> tableNames = new HashSet<>();
        for (InternalTableDefinition table : safeTables) {
            if (table == null) {
                errors.add("Internal table definition must be non-null.");
                continue;
            }
            validateTable(table, tableNames, errors);
        }
        return new InternalSchemaValidationResult(errors);
    }

    public static void validateOrThrow(List<InternalTableDefinition> tables) {
        InternalSchemaValidationResult result = validate(tables);
        if (!result.valid()) {
            throw new IllegalArgumentException(String.join(System.lineSeparator(), result.errors()));
        }
    }

    private static void validateTable(InternalTableDefinition table, Set<String> tableNames, List<String> errors) {
        String tableName = normalize(table.name());
        if (tableName.isBlank()) {
            errors.add("Internal table name must be non-blank.");
        } else {
            if (!tableName.startsWith("npdev_")) {
                errors.add("Internal table name must start with npdev_: " + table.name());
            }
            if (!tableNames.add(tableName)) {
                errors.add("Duplicate internal table name: " + table.name());
            }
        }

        if (table.columns().isEmpty()) {
            errors.add("Internal table must define at least one column: " + table.name());
        }

        Set<String> columnNames = new HashSet<>();
        for (InternalColumnDefinition column : table.columns()) {
            if (column == null) {
                errors.add("Internal column must be non-null in table " + table.name());
                continue;
            }
            String columnName = normalize(column.name());
            if (columnName.isBlank()) {
                errors.add("Internal column name must be non-blank in table " + table.name());
            } else if (!columnNames.add(columnName)) {
                errors.add("Duplicate internal column name: " + table.name() + "." + column.name());
            }
            if (column.type() == null) {
                errors.add("Internal column type must be non-null: " + table.name() + "." + column.name());
            }
        }

        validatePrimaryKey(table, columnNames, errors);
        validateIndexes(table, columnNames, errors);
    }

    private static void validatePrimaryKey(InternalTableDefinition table, Set<String> columnNames, List<String> errors) {
        if (table.primaryKey() == null || table.primaryKey().columns().isEmpty()) {
            errors.add("Internal table must define a primary key: " + table.name());
            return;
        }
        for (String primaryKeyColumn : table.primaryKey().columns()) {
            String columnName = normalize(primaryKeyColumn);
            if (columnName.isBlank()) {
                errors.add("Primary key column must be non-blank in table " + table.name());
            } else if (!columnNames.contains(columnName)) {
                errors.add("Primary key column is missing from table " + table.name() + ": " + primaryKeyColumn);
            }
        }
    }

    private static void validateIndexes(InternalTableDefinition table, Set<String> columnNames, List<String> errors) {
        Set<String> indexNames = new HashSet<>();
        for (InternalIndexDefinition index : table.indexes()) {
            if (index == null) {
                errors.add("Internal index must be non-null in table " + table.name());
                continue;
            }
            String indexName = normalize(index.name());
            if (indexName.isBlank()) {
                errors.add("Internal index name must be non-blank in table " + table.name());
            } else if (!indexNames.add(indexName)) {
                errors.add("Duplicate internal index name in table " + table.name() + ": " + index.name());
            }
            for (String indexColumn : index.columns()) {
                String columnName = normalize(indexColumn);
                if (columnName.isBlank()) {
                    errors.add("Internal index column must be non-blank in table " + table.name() + "." + index.name());
                } else if (!columnNames.contains(columnName)) {
                    errors.add("Index column is missing from table " + table.name() + "." + index.name() + ": " + indexColumn);
                }
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
