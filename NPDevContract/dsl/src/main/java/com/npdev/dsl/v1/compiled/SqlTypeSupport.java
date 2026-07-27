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
                // LIFT-UPLOAD-P2: a file field stores a FileHandle (or list, if multiple) as JSON --
                // bytes never go in the primary DB row, only the handle that locates them in a
                // FileStoreContract adapter.
                case "file" -> "JSONB";
                case "enum", "string" -> varcharType(field);
                default -> varcharType(field);
            };
        }

        String javaType = field == null ? null : field.getJavaType();
        if (javaType == null || javaType.isBlank()) {
            return varcharType(field);
        }
        String lower = javaType.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("uuid")) return "UUID";
        if (lower.contains("string")) return varcharType(field);
        if (lower.equals("int") || lower.contains("integer")) return "INTEGER";
        if (lower.equals("long") || lower.contains("long")) return "BIGINT";
        if (lower.contains("boolean")) return "BOOLEAN";
        if (lower.contains("bigdecimal")) return "NUMERIC(19,2)";
        if (lower.contains("localdate")) return "DATE";
        if (lower.contains("instant") || lower.contains("offsetdatetime")) return "TIMESTAMP WITH TIME ZONE";
        return varcharType(field);
    }

    /**
     * REG-53: a declared {@code maxLength} (`CompiledSchema.getMaxLength()`, the same JSON-Schema-
     * shaped constraint the input-validation layer already enforces at {@code DefaultSchemaValidator})
     * previously never reached this method at all -- every string/enum field got a hardcoded
     * {@code VARCHAR(255)} regardless, so a model author's declared length was validated on the way
     * in but never actually reflected in the physical column, and the schema diff had no way to see a
     * narrowing or widening since the "desired" type string never varied.
     * {@code SqlTypeNormalization}/{@code TypeChangeMatrix} already compare a VARCHAR's parenthesized
     * length numerically once given two different type strings -- this is the one place that needed
     * to actually produce one. 255 stays the default when no maxLength is declared, so every existing
     * model's generated DDL and fingerprint are unchanged.
     */
    private static String varcharType(CompiledField field) {
        Integer maxLength = field == null || field.getSchema() == null ? null : field.getSchema().getMaxLength();
        int length = maxLength != null && maxLength > 0 ? maxLength : 255;
        return "VARCHAR(" + length + ")";
    }
}
