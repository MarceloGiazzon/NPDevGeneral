package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class FlywayEmitter {

    /**
     * Generates one repeatable Flyway migration:
     *   R__npdev_schema.sql
     *
     * Why repeatable?
     * - It's ideal for generated schema because it can be re-generated safely.
     * - Flyway will re-run it when checksum changes.
     * - Avoids V1/V2 mismatch errors entirely.
     *
     * Notes:
     * - This is geared toward additive/constraint-enforcing evolution.
     * - Destructive changes (drop columns) are intentionally not generated.
     */
    public Path emitRepeatableSchema(CompiledModel model, Path canonicalMigrationsDir) throws Exception {
        Files.createDirectories(canonicalMigrationsDir);

        Path file = canonicalMigrationsDir.resolve("R__npdev_schema.sql");

        StringBuilder sb = new StringBuilder();
        sb.append("-- NPDev generated repeatable schema migration\n");
        sb.append("-- File: R__npdev_schema.sql\n");
        sb.append("-- Strategy:\n");
        sb.append("--  - CREATE TABLE IF NOT EXISTS\n");
        sb.append("--  - ALTER TABLE ADD COLUMN IF NOT EXISTS\n");
        sb.append("--  - SET NOT NULL for required fields\n");
        sb.append("--  - CREATE UNIQUE INDEX IF NOT EXISTS for unique fields\n");
        sb.append("--\n");
        sb.append("-- WARNING:\n");
        sb.append("--  - If required fields exist with NULLs, SET NOT NULL may fail.\n");
        sb.append("--  - Destructive changes (DROP COLUMN) are not generated.\n\n");

        for (CompiledConcept e : model.getConcepts()) {
            String table = safeTable(e);

            // 1) Ensure table exists (minimal definition)
            sb.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
            sb.append("  id UUID PRIMARY KEY\n");
            sb.append(");\n\n");

            // 2) Ensure columns exist (ADD COLUMN IF NOT EXISTS)
            for (CompiledField f : e.getFields()) {
                if (f.getName() == null) continue;
                if ("id".equalsIgnoreCase(f.getName())) continue;

                String col = toSnake(f.getName());
                String sqlType = mapType(f);

                sb.append("ALTER TABLE ").append(table)
                        .append(" ADD COLUMN IF NOT EXISTS ")
                        .append(col).append(" ").append(sqlType).append(";\n");
            }

            sb.append("\n");

            // 3) Enforce NOT NULL for required fields
            for (CompiledField f : e.getFields()) {
                if (f.getName() == null) continue;
                if ("id".equalsIgnoreCase(f.getName())) continue;

                boolean required = false;
                try { required = f.isRequired(); } catch (Exception ignored) {}

                if (required) {
                    String col = toSnake(f.getName());
                    sb.append("ALTER TABLE ").append(table)
                            .append(" ALTER COLUMN ").append(col)
                            .append(" SET NOT NULL;\n");
                }
            }

            sb.append("\n");

            // 4) Enforce uniqueness via portable indexes.
            // Generated services still perform case-insensitive String uniqueness checks before persistence.
            for (CompiledField f : e.getFields()) {
                if (f.getName() == null) continue;
                if ("id".equalsIgnoreCase(f.getName())) continue;

                boolean unique = false;
                try { unique = f.isUnique(); } catch (Exception ignored) {}

                if (!unique) continue;

                String col = toSnake(f.getName());
                String indexName = "ux_" + table + "_" + col;

                if (indexName.length() > 60) {
                    indexName = indexName.substring(0, 60);
                }

                sb.append("CREATE UNIQUE INDEX IF NOT EXISTS ")
                        .append(indexName)
                        .append(" ON ")
                        .append(table)
                        .append(" (")
                        .append(col)
                        .append(");\n");
            }

            sb.append("\n-- ----\n\n");
        }

        Files.writeString(
                file,
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        return file;
    }

    private String safeTable(CompiledConcept e) {
        String t = null;
        try { t = e.getTableName(); } catch (Exception ignored) {}
        if (t == null || t.trim().isEmpty()) t = e.getName().toLowerCase(Locale.ROOT) + "s";
        return t;
    }

    private String toSnake(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) out.append('_');
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private String mapType(CompiledField field) {
        String dslType = field == null ? null : field.getDslType();
        if (dslType != null && !dslType.isBlank()) {
            String normalizedDslType = dslType.trim().toLowerCase(Locale.ROOT);
            if ("reference".equals(normalizedDslType) || "uuid".equals(normalizedDslType)) return "UUID";
            if ("int".equals(normalizedDslType) || "integer".equals(normalizedDslType)) return "INTEGER";
            if ("long".equals(normalizedDslType)) return "BIGINT";
            if ("boolean".equals(normalizedDslType)) return "BOOLEAN";
            if ("date".equals(normalizedDslType)) return "DATE";
            if ("datetime".equals(normalizedDslType)) return "TIMESTAMP WITH TIME ZONE";
            if ("enum".equals(normalizedDslType)) return "VARCHAR(255)";
            if ("object".equals(normalizedDslType) || "array".equals(normalizedDslType)) return "JSONB";
        }

        String javaType = field == null ? null : field.getJavaType();
        if (javaType == null) return "VARCHAR(255)";
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
