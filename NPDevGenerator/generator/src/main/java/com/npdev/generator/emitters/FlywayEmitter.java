package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
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

            sb.append("ALTER TABLE ").append(table).append(" ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;\n");
            sb.append("UPDATE ").append(table).append(" SET version = 0 WHERE version IS NULL;\n");
            sb.append("ALTER TABLE ").append(table).append(" ALTER COLUMN version SET NOT NULL;\n");
            sb.append("ALTER TABLE ").append(table).append(" ALTER COLUMN version SET DEFAULT 0;\n\n");

            for (CompiledField f : e.getFields()) {
                if (f == null || f.getName() == null || f.isId() || isManyToManyBond(e, f, conceptsByName)) {
                    continue;
                }
                sb.append("ALTER TABLE ").append(table)
                        .append(" ADD COLUMN IF NOT EXISTS ")
                        .append(SqlIdentifierSupport.columnName(f)).append(" ").append(columnType(e, f, conceptsByName)).append(";\n");
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
                    // A connectable natural-key anchor is an FK target. H2 rejects a unique INDEX as
                    // an FK target (error 90057); a UNIQUE CONSTRAINT satisfies both requirements.
                    // Wrapped in a DO $$ guard so the repeatable migration is idempotent.
                    String constraint = SqlIdentifierSupport.safeSqlIdentifier("uq_" + table + "_" + col);
                    sb.append(addConstraintIfMissing(
                            table, constraint,
                            "ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " UNIQUE (" + col + ")"));
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
            String sourceConstraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + sourceColumn);
            String sourceConstraintSql = "ALTER TABLE " + junctionTable
                    + " ADD CONSTRAINT " + sourceConstraint
                    + " FOREIGN KEY (" + sourceColumn + ")"
                    + " REFERENCES " + bond.sourceTable() + " (" + SqlIdentifierSupport.columnName(sourceId) + ")"
                    + " ON DELETE CASCADE";
            sb.append(addConstraintIfMissing(junctionTable, sourceConstraint, sourceConstraintSql));
            String targetConstraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + targetColumn);
            String targetConstraintSql = "ALTER TABLE " + junctionTable
                    + " ADD CONSTRAINT " + targetConstraint
                    + " FOREIGN KEY (" + targetColumn + ")"
                    + " REFERENCES " + bond.targetTable() + " (" + bond.anchorColumn() + ")"
                    + bond.onUpdateSqlClause()
                    + " ON DELETE " + bond.onDeleteSql();
            sb.append(addConstraintIfMissing(junctionTable, targetConstraint, targetConstraintSql)).append("\n");
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
            String constraintSql = "ALTER TABLE " + bond.sourceTable()
                    + " ADD CONSTRAINT " + constraint
                    + " FOREIGN KEY (" + bond.sourceColumn() + ")"
                    + " REFERENCES " + bond.targetTable() + " (" + bond.anchorColumn() + ")"
                    + bond.onUpdateSqlClause()
                    + " ON DELETE " + bond.onDeleteSql();
            sb.append(addConstraintIfMissing(bond.sourceTable(), constraint, constraintSql));
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

    /** Column SQL type: for a declared bond, match the bound anchor's type; otherwise the field's own type. */
    private String columnType(
            CompiledConcept sourceConcept,
            CompiledField field,
            Map<String, CompiledConcept> conceptsByName
    ) {
        return BondModelSupport.resolveBond(sourceConcept, field, conceptsByName)
                .map(Bond::effectiveSqlType)
                .orElseGet(() -> mapType(field));
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

    private static String addConstraintIfMissing(String tableName, String constraintName, String addConstraintSql) {
        String statement = addConstraintSql.endsWith(";") ? addConstraintSql : addConstraintSql + ";";
        // INFORMATION_SCHEMA.TABLE_CONSTRAINTS is standard SQL and available in both PostgreSQL
        // and H2 PostgreSQL-compatibility mode. pg_constraint/pg_class/pg_namespace are
        // PostgreSQL-only system catalogs; H2 does not expose them, so using them would break
        // every H2-backed FinalApp on the R__ repeatable migration.
        return """
                DO $$
                BEGIN
                  IF NOT EXISTS (
                    SELECT 1
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                    WHERE CONSTRAINT_NAME = '%s'
                      AND TABLE_NAME = '%s'
                      AND TABLE_SCHEMA = current_schema()
                  ) THEN
                    %s
                  END IF;
                END $$;
                """.formatted(
                sqlLiteral(constraintName),
                sqlLiteral(tableName),
                statement
        );
    }

    private static String sqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
