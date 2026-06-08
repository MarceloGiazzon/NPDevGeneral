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
     *   R__npdev_business_concepts.sql
     *
     * Runtime migrations and business migrations intentionally share the Flyway
     * location but not the same files. Runtime tables store execution/audit/trace
     * data; these tables store generated business concept data.
     */
    public Path emitRepeatableSchema(CompiledModel model, Path canonicalMigrationsDir) throws Exception {
        Files.createDirectories(canonicalMigrationsDir);

        Files.deleteIfExists(canonicalMigrationsDir.resolve("R__npdev_schema.sql"));
        Path file = canonicalMigrationsDir.resolve("R__npdev_business_concepts.sql");

        StringBuilder sb = new StringBuilder();
        sb.append("-- NPDev generated repeatable business concept schema migration\n");
        sb.append("-- File: R__npdev_business_concepts.sql\n");
        sb.append("-- Storage boundary:\n");
        sb.append("--  - NPDev runtime tables store execution, audit, trace, scheduling, and reliability data.\n");
        sb.append("--  - These generated concept tables store business application data.\n");
        sb.append("-- Strategy:\n");
        sb.append("--  - CREATE TABLE IF NOT EXISTS\n");
        sb.append("--  - ALTER TABLE ADD COLUMN IF NOT EXISTS\n");
        sb.append("--  - SET NOT NULL for required fields\n");
        sb.append("--  - CREATE UNIQUE INDEX IF NOT EXISTS for unique fields\n");
        sb.append("--  - CREATE INDEX IF NOT EXISTS for reference-like fields\n");
        sb.append("--\n");
        sb.append("-- WARNING:\n");
        sb.append("--  - If required fields exist with NULLs, SET NOT NULL may fail.\n");
        sb.append("--  - Destructive changes (DROP COLUMN) are not generated.\n\n");

        for (CompiledConcept e : model.getConcepts()) {
            String table = safeTable(e);
            CompiledField idField = idField(e);
            String idColumn = toSnake(idField.getName());

            sb.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
            sb.append("  ").append(idColumn).append(" ").append(mapType(idField)).append(" PRIMARY KEY\n");
            sb.append(");\n\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId()) {
                    continue;
                }
                sb.append("ALTER TABLE ").append(table)
                        .append(" ADD COLUMN IF NOT EXISTS ")
                        .append(toSnake(f.getName())).append(" ").append(mapType(f)).append(";\n");
            }

            sb.append("\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId()) {
                    continue;
                }
                boolean required = false;
                try { required = f.isRequired(); } catch (Exception ignored) {}
                if (required) {
                    sb.append("ALTER TABLE ").append(table)
                            .append(" ALTER COLUMN ").append(toSnake(f.getName()))
                            .append(" SET NOT NULL;\n");
                }
            }

            sb.append("\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId()) {
                    continue;
                }
                boolean unique = false;
                try { unique = f.isUnique(); } catch (Exception ignored) {}
                if (!unique) {
                    continue;
                }
                String col = toSnake(f.getName());
                sb.append("CREATE UNIQUE INDEX IF NOT EXISTS ")
                        .append(truncateIdentifier("ux_" + table + "_" + col))
                        .append(" ON ")
                        .append(table)
                        .append(" (")
                        .append(col)
                        .append(");\n");
            }

            sb.append("\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId() || !isReferenceLike(f)) {
                    continue;
                }
                String col = toSnake(f.getName());
                sb.append("CREATE INDEX IF NOT EXISTS ")
                        .append(truncateIdentifier("idx_" + table + "_" + col))
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

    private static CompiledField idField(CompiledConcept e) {
        CompiledField found = null;
        for (CompiledField field : e.getFields()) {
            if (field == null || !field.isId()) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("Concept " + e.getName() + " must have exactly one id field.");
            }
            found = field;
        }
        if (found == null) {
            throw new IllegalStateException("Concept " + e.getName() + " must have exactly one id field.");
        }
        return found;
    }

    private String safeTable(CompiledConcept e) {
        String t = null;
        try { t = e.getTableName(); } catch (Exception ignored) {}
        if (t == null || t.trim().isEmpty()) t = e.getName().toLowerCase(Locale.ROOT) + "s";
        return t.trim().toLowerCase(Locale.ROOT);
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

    private static boolean isReferenceLike(CompiledField field) {
        String dslType = field.getDslType();
        if (dslType != null && "reference".equalsIgnoreCase(dslType.trim())) {
            return true;
        }
        String name = field.getName();
        String javaType = field.getJavaType();
        return name != null
                && name.endsWith("Id")
                && javaType != null
                && javaType.toLowerCase(Locale.ROOT).contains("uuid");
    }

    private static String truncateIdentifier(String value) {
        return value.length() > 60 ? value.substring(0, 60) : value;
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
