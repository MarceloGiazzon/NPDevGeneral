package com.npdev.dsl.v1.compiled;

import java.util.Locale;

/**
 * Shared SQL type mapping for compiled DSL fields.
 * Generator DDL, bond DDL, and database-definition fingerprints use this class.
 */
public final class SqlTypeSupport {
    private SqlTypeSupport() {
    }

    public static String sqlType(CompiledField field) {
        String dslType = field == null ? null : field.getDslType();
        if (dslType != null && !dslType.isBlank()) {
            return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
                case "reference", "uuid" -> "UUID";
                case "int", "integer" -> "INTEGER";
                case "long" -> "BIGINT";
                case "boolean" -> "BOOLEAN";
                case "date" -> "DATE";
                case "datetime" -> "TIMESTAMP WITH TIME ZONE";
                case "object", "array" -> "JSONB";
                case "enum", "string" -> "VARCHAR(255)";
                default -> "VARCHAR(255)";
            };
        }

        String javaType = field == null ? null : field.getJavaType();
        if (javaType == null || javaType.isBlank()) {
            return "VARCHAR(255)";
        }
        String lower = javaType.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("uuid")) return "UUID";
        if (lower.contains("string")) return "VARCHAR(255)";
        if (lower.equals("int") || lower.contains("integer")) return "INTEGER";
        if (lower.equals("long") || lower.contains("long")) return "BIGINT";
        if (lower.contains("boolean")) return "BOOLEAN";
        if (lower.contains("bigdecimal")) return "NUMERIC(19,2)";
        if (lower.contains("localdate")) return "DATE";
        if (lower.contains("instant") || lower.contains("offsetdatetime")) return "TIMESTAMP WITH TIME ZONE";
        return "VARCHAR(255)";
    }
}
