package com.npdev.generator.migration;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StorageSchemaFromCompiledModel {

    public StorageSchemaSnapshot from(CompiledModel model) {
        if (model == null) {
            return new StorageSchemaSnapshot("unknown", List.of());
        }
        List<StorageTableSchema> tables = new ArrayList<>();
        for (CompiledConcept entity : model.getConcepts()) {
            if (entity == null || entity.getName() == null || entity.getName().isBlank()) {
                continue;
            }
            List<StorageColumnSchema> columns = new ArrayList<>();
            columns.add(new StorageColumnSchema("id", "UUID", true, true));
            for (CompiledField field : entity.getFields()) {
                if (field == null || field.getName() == null || field.getName().isBlank()) {
                    continue;
                }
                if ("id".equalsIgnoreCase(field.getName())) {
                    continue;
                }
                columns.add(new StorageColumnSchema(
                        toSnake(field.getName()),
                        mapType(field),
                        safeRequired(field),
                        safeUnique(field)
                ));
            }
            tables.add(new StorageTableSchema(resolveTableName(entity), columns));
        }
        return new StorageSchemaSnapshot("compiled-model", tables).normalized();
    }

    private static String resolveTableName(CompiledConcept entity) {
        if (entity == null) {
            return "";
        }
        String explicit = entity.getTableName();
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        return toSnakePlural(entity.getName());
    }

    private static boolean safeRequired(CompiledField field) {
        try {
            return field.isRequired();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean safeUnique(CompiledField field) {
        try {
            return field.isUnique();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String mapType(CompiledField field) {
        String dslType = field == null ? null : field.getDslType();
        if (dslType != null && !dslType.isBlank()) {
            return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
                case "uuid", "reference" -> "UUID";
                case "int", "integer" -> "INTEGER";
                case "long" -> "BIGINT";
                case "boolean" -> "BOOLEAN";
                case "date" -> "DATE";
                case "datetime" -> "TIMESTAMP WITH TIME ZONE";
                case "enum" -> "VARCHAR";
                case "object", "array" -> "JSONB";
                default -> "VARCHAR";
            };
        }

        String javaType = field == null ? null : field.getJavaType();
        if (javaType == null || javaType.isBlank()) {
            return "TEXT";
        }
        return switch (javaType.trim()) {
            case "String" -> "VARCHAR";
            case "Integer", "int" -> "INTEGER";
            case "Long", "long" -> "BIGINT";
            case "Boolean", "boolean" -> "BOOLEAN";
            case "BigDecimal" -> "NUMERIC";
            case "java.time.LocalDate" -> "DATE";
            case "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.time.Instant" -> "TIMESTAMP WITH TIME ZONE";
            case "UUID" -> "UUID";
            default -> "TEXT";
        };
    }

    private static String toSnake(String value) {
        String s = value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .toLowerCase(Locale.ROOT);
        return s.replaceAll("^_+|_+$", "");
    }

    private static String toSnakePlural(String value) {
        String base = toSnake(value);
        if (base.endsWith("s")) {
            return base;
        }
        return base + "s";
    }
}
