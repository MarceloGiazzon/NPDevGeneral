package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.generator.bonds.BondModelSupport;
import com.npdev.generator.bonds.BondModelSupport.Bond;
import com.npdev.generator.bonds.BondModelSupport.Cardinality;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

        Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);

        for (CompiledConcept e : model.getConcepts()) {
            String table = safeTable(e);
            CompiledField idField = BondModelSupport.idField(e);
            String idColumn = SqlIdentifierSupport.columnName(idField);

            sb.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
            sb.append("  ").append(idColumn).append(" ").append(mapType(idField)).append(" PRIMARY KEY\n");
            sb.append(");\n\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId() || isManyToManyBond(e, f, conceptsByName)) {
                    continue;
                }
                sb.append("ALTER TABLE ").append(table)
                        .append(" ADD COLUMN IF NOT EXISTS ")
                        .append(SqlIdentifierSupport.columnName(f)).append(" ").append(columnType(f, conceptsByName)).append(";\n");
            }

            sb.append("\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId() || isManyToManyBond(e, f, conceptsByName)) {
                    continue;
                }
                boolean required = false;
                try { required = f.isRequired(); } catch (Exception ignored) {}
                if (required) {
                    sb.append("ALTER TABLE ").append(table)
                            .append(" ALTER COLUMN ").append(SqlIdentifierSupport.columnName(f))
                            .append(" SET NOT NULL;\n");
                }
            }

            sb.append("\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId() || isManyToManyBond(e, f, conceptsByName)) {
                    continue;
                }
                boolean unique = false;
                try { unique = f.isUnique(); } catch (Exception ignored) {}
                if (!unique) {
                    continue;
                }
                String col = SqlIdentifierSupport.columnName(f);
                if (isConnectableAnchor(f)) {
                    // A connectable natural-key anchor is an FK target. On H2 a unique INDEX is not a
                    // valid FK target (error 90057); a UNIQUE CONSTRAINT is, and it backs uniqueness
                    // too. Plain unique fields keep a unique index.
                    sb.append("ALTER TABLE ").append(table)
                            .append(" ADD CONSTRAINT IF NOT EXISTS ")
                            .append(SqlIdentifierSupport.safeSqlIdentifier("uq_" + table + "_" + col))
                            .append(" UNIQUE (")
                            .append(col)
                            .append(");\n");
                } else {
                    sb.append("CREATE UNIQUE INDEX IF NOT EXISTS ")
                            .append(SqlIdentifierSupport.safeSqlIdentifier("ux_" + table + "_" + col))
                            .append(" ON ")
                            .append(table)
                            .append(" (")
                            .append(col)
                            .append(");\n");
                }
            }

            sb.append("\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId() || isManyToManyBond(e, f, conceptsByName) || !isReferenceLike(f)) {
                    continue;
                }
                String col = SqlIdentifierSupport.columnName(f);
                sb.append("CREATE INDEX IF NOT EXISTS ")
                        .append(SqlIdentifierSupport.safeSqlIdentifier("idx_" + table + "_" + col))
                        .append(" ON ")
                        .append(table)
                        .append(" (")
                        .append(col)
                        .append(");\n");
            }

            sb.append("\n-- ----\n\n");
        }

        List<Bond> bonds = BondModelSupport.allBonds(model);

        // Junction tables for pure-pointer N:M bonds. The authored field is not a
        // scalar column; the set lives entirely in this synthesized table.
        sb.append("-- Junction tables (N:M bonds)\n");
        for (Bond bond : bonds) {
            if (bond.cardinality() != Cardinality.MANY_TO_MANY) {
                continue;
            }
            CompiledField sourceId = BondModelSupport.idField(bond.sourceConcept());
            String junctionTable = bond.junctionTable();
            String sourceColumn = SqlIdentifierSupport.sourceJunctionColumn(sourceId);
            String targetColumn = SqlIdentifierSupport.targetJunctionColumn(bond.anchorField());
            sb.append("CREATE TABLE IF NOT EXISTS ").append(junctionTable).append(" (\n")
                    .append("  ").append(sourceColumn).append(" ").append(mapType(sourceId)).append(" NOT NULL,\n")
                    .append("  ").append(targetColumn).append(" ").append(bond.effectiveSqlType()).append(" NOT NULL,\n")
                    .append("  PRIMARY KEY (").append(sourceColumn).append(", ").append(targetColumn).append(")\n")
                    .append(");\n");
            sb.append("CREATE INDEX IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("idx_" + junctionTable + "_" + sourceColumn))
                    .append(" ON ").append(junctionTable).append(" (").append(sourceColumn).append(");\n");
            sb.append("CREATE INDEX IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("idx_" + junctionTable + "_" + targetColumn))
                    .append(" ON ").append(junctionTable).append(" (").append(targetColumn).append(");\n");
            // Source-side FK is always CASCADE: a junction row is membership owned by its
            // source, so deleting the source must remove its memberships. The authored
            // onDelete policy governs only the TARGET side below.
            sb.append("ALTER TABLE ").append(junctionTable)
                    .append(" ADD CONSTRAINT IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + sourceColumn))
                    .append(" FOREIGN KEY (").append(sourceColumn).append(")")
                    .append(" REFERENCES ").append(bond.sourceTable()).append(" (").append(SqlIdentifierSupport.columnName(sourceId)).append(")")
                    .append(" ON DELETE CASCADE;\n");
            sb.append("ALTER TABLE ").append(junctionTable)
                    .append(" ADD CONSTRAINT IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + targetColumn))
                    .append(" FOREIGN KEY (").append(targetColumn).append(")")
                    .append(" REFERENCES ").append(bond.targetTable()).append(" (").append(bond.anchorColumn()).append(")")
                    .append(bond.onUpdateSqlClause())
                    .append(" ON DELETE ").append(bond.onDeleteSql()).append(";\n\n");
        }

        // Foreign keys (bonds) emitted last: every target table and its PK/unique
        // anchor column already exists above, so the references resolve. DB is the
        // source of truth for referential integrity; the app maps violations to a
        // clean domain error.
        sb.append("-- Foreign keys (bonds)\n");
        for (Bond bond : bonds) {
            if (bond.cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            String constraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + bond.sourceTable() + "_" + bond.sourceColumn());
            sb.append("ALTER TABLE ").append(bond.sourceTable())
                    .append(" ADD CONSTRAINT IF NOT EXISTS ").append(constraint)
                    .append(" FOREIGN KEY (").append(bond.sourceColumn()).append(")")
                    .append(" REFERENCES ").append(bond.targetTable()).append(" (").append(bond.anchorColumn()).append(")")
                    .append(bond.onUpdateSqlClause())
                    .append(" ON DELETE ").append(bond.onDeleteSql()).append(";\n");
        }
        sb.append("\n");

        Files.writeString(
                file,
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        return file;
    }

    private static boolean isManyToManyBond(
            CompiledConcept source,
            CompiledField field,
            Map<String, CompiledConcept> conceptsByName
    ) {
        return BondModelSupport.resolveBond(source, field, conceptsByName)
                .map(bond -> bond.cardinality() == Cardinality.MANY_TO_MANY)
                .orElse(false);
    }

    private String safeTable(CompiledConcept e) {
        return SqlIdentifierSupport.tableName(e);
    }

    private String toSnake(String s) {
        return SqlIdentifierSupport.toSnake(s);
    }

    /** A field explicitly marked as a connectable bond anchor (an FK-referenceable natural key). */
    private static boolean isConnectableAnchor(CompiledField field) {
        return field != null && "anchor".equalsIgnoreCase(field.getConnectable());
    }

    /** A field that declares a real bond target (not the {@code *Id}-uuid heuristic). */
    private static boolean isDeclaredReference(CompiledField field) {
        CompiledReferenceSemantics rs = field.getReferenceSemantics();
        if (rs != null && rs.getTarget() != null && !rs.getTarget().isBlank()) {
            return true;
        }
        return field.getReferenceTarget() != null && !field.getReferenceTarget().isBlank();
    }

    /** Resolves the anchor field a port binds to (its {@code via}, or the target id). */
    private CompiledField resolveAnchorField(CompiledField field, Map<String, CompiledConcept> conceptsByName) {
        CompiledReferenceSemantics rs = field.getReferenceSemantics();
        String targetName = rs != null && rs.getTarget() != null && !rs.getTarget().isBlank()
                ? rs.getTarget()
                : field.getReferenceTarget();
        if (targetName == null || targetName.isBlank()) {
            return null;
        }
        CompiledConcept target = conceptsByName.get(targetName.toLowerCase(Locale.ROOT));
        if (target == null) {
            return null;
        }
        String via = rs == null ? null : rs.getVia();
        if (via == null || via.isBlank()) {
            return idFieldOrNull(target);
        }
        return fieldByName(target, via);
    }

    /** Column SQL type: for a declared bond, match the bound anchor's type; otherwise the field's own type. */
    private String columnType(CompiledField field, Map<String, CompiledConcept> conceptsByName) {
        if (isDeclaredReference(field)) {
            CompiledField anchor = resolveAnchorField(field, conceptsByName);
            if (anchor != null) {
                return mapType(anchor);
            }
        }
        return mapType(field);
    }

    private static CompiledField idFieldOrNull(CompiledConcept concept) {
        for (CompiledField field : concept.getFields()) {
            if (field != null && field.isId()) {
                return field;
            }
        }
        return null;
    }

    private static CompiledField fieldByName(CompiledConcept concept, String name) {
        for (CompiledField field : concept.getFields()) {
            if (field != null && name.equalsIgnoreCase(field.getName())) {
                return field;
            }
        }
        return null;
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
        return SqlIdentifierSupport.safeSqlIdentifier(value);
    }

    private String mapType(CompiledField field) {
        return SqlTypeSupport.sqlType(field);
    }
}
