package com.finalexec.npdev.migration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StorageSchemaFromCompiledModel {

    public StorageSchemaSnapshot from(Object compiledModel) {
        JsonNode root = MigrationSharedSupport.toJson(compiledModel);
        String modelVersion = readText(root, "version", readText(root, "dslVersion", "unknown"));
        JsonNode concepts = root.path("concepts");
        List<StorageTableSchema> tables = new ArrayList<>();

        if (concepts.isArray()) {
            for (JsonNode concept : concepts) {
                String conceptName = readText(concept, "name", "concept");
                String tableName = toTableName(conceptName);
                List<StorageColumnSchema> columns = new ArrayList<>();
                JsonNode fields = concept.path("fields");
                if (fields.isArray()) {
                    for (JsonNode field : fields) {
                        columns.add(new StorageColumnSchema(
                                toColumnName(readText(field, "name", "field")),
                                toSqlType(field),
                                field.path("required").asBoolean(false),
                                field.path("unique").asBoolean(false) || field.path("id").asBoolean(false)
                        ));
                    }
                }
                tables.add(new StorageTableSchema(tableName, columns));
            }
        }

        return new StorageSchemaSnapshot(modelVersion, tables).normalized();
    }

    private static String toTableName(String conceptName) {
        String value = conceptName == null ? "concept" : conceptName.trim();
        String lower = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('-', '_').toLowerCase(Locale.ROOT);
        lower = lower.replace("_", "");
        return lower.endsWith("s") ? lower : lower + "s";
    }

    private static String toColumnName(String fieldName) {
        return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String toSqlType(JsonNode field) {
        if (field.hasNonNull("ref")) {
            return "UUID";
        }
        String type = readText(field, "type", "string").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "uuid" -> "UUID";
            case "int", "integer" -> "INTEGER";
            case "long" -> "BIGINT";
            case "bool", "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "datetime", "timestamp", "instant" -> "TIMESTAMPTZ";
            case "json", "jsonb", "object", "array" -> "JSONB";
            default -> "VARCHAR";
        };
    }

    private static String readText(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }
}
