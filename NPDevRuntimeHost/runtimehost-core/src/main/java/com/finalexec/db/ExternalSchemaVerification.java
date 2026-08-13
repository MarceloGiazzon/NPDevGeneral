package com.finalexec.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): REG-7.1's {@code schemaLifecycle.ownership=ExternallyManaged}
 * read-only compatibility check, split out of {@link SchemaLifecycleExecutor} verbatim -- no behavior
 * change. All of these were {@code private static} and reachable only from
 * {@link SchemaLifecycleExecutor#verifyExternallyManagedSchemaCompatible} (which stays on the
 * executor as a thin delegating wrapper -- it is directly unit-tested against a real H2
 * {@link DataSource}), so they move here in full. Flat sibling in {@code com.finalexec.db}, not a
 * subpackage -- see {@link TableRenamePass}'s class javadoc for why.
 */
final class ExternalSchemaVerification {

    private ExternalSchemaVerification() {
    }

    /**
     * REG-7.1 (D5): the read-only compatibility check itself. Returns one itemized, human-readable
     * problem string per incompatibility; empty when the live schema fully satisfies this build's model.
     *
     * <p><b>SER-P5.2 — full-shape, not column-shape.</b> This used to check only table existence, column
     * existence and column type, so an external schema that matched every column NAME but violated the
     * model's nullability or uniqueness assumptions verified "compatible" and failed later at runtime.
     * It now also checks <b>nullability</b> (both fatal directions — see
     * {@link #appendExternalNullabilityProblem}) and <b>unique constraints</b> the model declares
     * ({@link #appendExternalUniqueProblems}), sourcing those facts from the canonical
     * {@code CurrentSchema}/{@code DesiredSchema} pair rather than a second hand-rolled introspection
     * (REG-6: one notion of "does the live schema match", never a second, drifting one).
     *
     * <p><b>Still not checked (deferred):</b> foreign keys and indexes. The manifest carries no explicit
     * FK/index lists (the P0.2 asymmetry — they are derived at generation), so the desired side cannot yet
     * express them; {@code CurrentSchemaReader} already reads them for when it can. Until then an external
     * schema missing a bond's FK or an index verifies clean.
     */
    static List<String> findExternalSchemaIncompatibilities(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        List<String> problems = new ArrayList<>();
        // SER-P5.2: the FULL-SHAPE facts (nullability, uniques) come from the canonical CurrentSchema +
        // DesiredSchema -- the same models classify/SchemaDeltaReport/the Impact Report consume -- so this
        // verification is no longer a second, drifting notion of "does the live schema match" (REG-6).
        // Read once, outside the metadata loop below.
        com.finalexec.db.schemastate.CurrentSchema fullCurrent =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.DesiredSchema fullDesired = DesiredSchemaFactory.fromManifest(manifest);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                Set<String> live = SchemaLifecycleExecutor.readActualColumns(metadata, table);
                if (live.isEmpty()) {
                    problems.add(table + " (table missing)");
                    continue;
                }
                String tableKey = table.toLowerCase(Locale.ROOT);
                com.finalexec.db.schemastate.CurrentTable liveTable = fullCurrent.tables().get(tableKey);
                com.finalexec.db.schemastate.DesiredTable desiredTable = fullDesired.tables().get(tableKey);
                Map<String, String> expectedTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
                Map<String, String> actualTypes = SchemaLifecycleExecutor.readActualColumnTypes(metadata, table);
                for (String column : entry.getValue()) {
                    String normalized = column.toLowerCase(Locale.ROOT);
                    if (!live.contains(normalized)) {
                        problems.add(table + "." + column + " (column missing)");
                        continue;
                    }
                    String expectedType = SchemaLifecycleExecutor.normalizeSqlType(expectedTypes.get(column));
                    String actualType = SchemaLifecycleExecutor.normalizeSqlType(actualTypes.get(column));
                    if (expectedType != null && actualType != null && !expectedType.equals(actualType)) {
                        problems.add(table + "." + column + " (type mismatch: model expects " + expectedType
                                + ", live schema has " + actualType + ")");
                    }
                    appendExternalNullabilityProblem(problems, table, column, liveTable, desiredTable, normalized);
                }
                appendExternalUniqueProblems(problems, table, liveTable, desiredTable);
                appendExternalForeignKeyAndIndexProblems(problems, table, liveTable, manifest);
            }
        } catch (SQLException exception) {
            problems.add("(failed introspecting live schema: " + exception.getMessage() + ")");
        }
        return problems;
    }

    /**
     * SER-P5.2: the nullability half of the full-shape {@code ExternallyManaged} check. Two genuinely
     * fatal directions, each reported with the reason it breaks at RUNTIME (this path refuses a boot, so
     * it must not flag a difference that would in fact work):
     * <ul>
     *   <li><b>model requires NOT NULL, live is nullable</b> — the app can read a {@code null} into a
     *       field its model says always has a value.</li>
     *   <li><b>live is NOT NULL with NO default, model treats it as optional</b> — an insert that omits
     *       the column fails outright. The {@code no default} qualifier matters: a live {@code NOT NULL
     *       DEFAULT …} column is perfectly safe for an optional model field, so it is NOT flagged.</li>
     * </ul>
     */
    private static void appendExternalNullabilityProblem(List<String> problems, String table, String column,
            com.finalexec.db.schemastate.CurrentTable liveTable,
            com.finalexec.db.schemastate.DesiredTable desiredTable, String columnKey) {
        if (liveTable == null || desiredTable == null) {
            return;
        }
        com.finalexec.db.schemastate.CurrentColumn liveColumn = liveTable.columns().get(columnKey);
        com.finalexec.db.schemastate.DesiredColumn desiredColumn = desiredTable.columns().get(columnKey);
        if (liveColumn == null || desiredColumn == null) {
            return;
        }
        if (!desiredColumn.nullable() && liveColumn.nullable()) {
            problems.add(table + "." + column + " (nullability mismatch: the model requires a value (NOT NULL) "
                    + "but the live column is nullable)");
        } else if (desiredColumn.nullable() && !liveColumn.nullable()
                && liveColumn.defaultValueNormalized() == null) {
            problems.add(table + "." + column + " (nullability mismatch: the live column is NOT NULL with no "
                    + "default but the model treats it as optional -- an insert that omits it will fail)");
        }
    }

    /**
     * SER-P5.2: the uniqueness half. A unique invariant the MODEL declares but the live schema does not
     * enforce is a silent data-integrity hole (the app assumes uniqueness nothing guarantees). A live
     * unique constraint the model does NOT declare is deliberately tolerated — an externally-managed
     * schema is allowed to be stricter than the model. A primary key covering exactly the same columns
     * satisfies the requirement.
     */
    private static void appendExternalUniqueProblems(List<String> problems, String table,
            com.finalexec.db.schemastate.CurrentTable liveTable,
            com.finalexec.db.schemastate.DesiredTable desiredTable) {
        if (liveTable == null || desiredTable == null) {
            return;
        }
        for (com.finalexec.db.schemastate.DesiredUniqueConstraint wanted : desiredTable.uniques()) {
            boolean satisfied = sameColumnSet(liveTable.primaryKeyColumns(), wanted.columns());
            for (com.finalexec.db.schemastate.CurrentUniqueConstraint live : liveTable.uniques()) {
                satisfied = satisfied || sameColumnSet(live.columns(), wanted.columns());
            }
            if (!satisfied) {
                problems.add(table + " (missing unique constraint on " + wanted.columns()
                        + ": the model declares this combination unique but the live schema does not enforce it)");
            }
        }
    }

    /**
     * SER-G8: the foreign-key and index half of the full-shape {@code ExternallyManaged} check — the last
     * dimension P5.2 could not cover until the manifest carried explicit FK/index lists.
     *
     * <p><b>Missing-only, matched by column set.</b> A live schema is allowed to have EXTRA FKs and extra
     * indexes (an external DBA's performance indexes, and every engine's implicit PK/unique-backing index);
     * only something the MODEL declares and the live schema lacks is reported. Matching deliberately
     * ignores constraint/index NAMES — they are engine-generated ({@code PRIMARY_KEY_5} on H2,
     * {@code widgets_pkey} on Postgres) and would produce pure noise. A unique constraint or primary key
     * over the same columns satisfies a declared index.
     */
    private static void appendExternalForeignKeyAndIndexProblems(List<String> problems, String table,
            com.finalexec.db.schemastate.CurrentTable liveTable, SchemaLifecycleExecutor.SchemaManifest manifest) {
        if (liveTable == null) {
            return;
        }
        for (SchemaLifecycleExecutor.ForeignKeyDecl wanted : manifest.businessTableForeignKeys().getOrDefault(table, List.of())) {
            boolean satisfied = false;
            for (com.finalexec.db.schemastate.CurrentForeignKey live : liveTable.foreignKeys()) {
                satisfied = satisfied || (sameColumnSet(live.columns(), wanted.columns())
                        && live.referencedTable() != null
                        && live.referencedTable().equalsIgnoreCase(wanted.referencedTable()));
            }
            if (!satisfied) {
                problems.add(table + " (missing foreign key on " + wanted.columns() + " -> "
                        + wanted.referencedTable() + ": the model declares this bond but the live schema "
                        + "does not enforce referential integrity for it)");
            }
        }
        for (SchemaLifecycleExecutor.IndexDecl wanted : manifest.businessTableIndexes().getOrDefault(table, List.of())) {
            boolean satisfied = sameColumnSet(liveTable.primaryKeyColumns(), wanted.columns());
            for (com.finalexec.db.schemastate.CurrentIndex live : liveTable.indexes()) {
                satisfied = satisfied || (sameColumnSet(live.columns(), wanted.columns())
                        && (!wanted.unique() || live.unique()));
            }
            for (com.finalexec.db.schemastate.CurrentUniqueConstraint live : liveTable.uniques()) {
                satisfied = satisfied || sameColumnSet(live.columns(), wanted.columns());
            }
            if (!satisfied) {
                problems.add(table + " (missing " + (wanted.unique() ? "unique " : "") + "index on "
                        + wanted.columns() + ": the model declares it but the live schema does not have it)");
            }
        }
    }

    /** Order-insensitive, case-insensitive column-set equality for constraint comparison. */
    private static boolean sameColumnSet(List<String> a, List<String> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        Set<String> left = new LinkedHashSet<>();
        for (String value : a) {
            left.add(value == null ? "" : value.toLowerCase(Locale.ROOT));
        }
        Set<String> right = new LinkedHashSet<>();
        for (String value : b) {
            right.add(value == null ? "" : value.toLowerCase(Locale.ROOT));
        }
        return left.equals(right);
    }
}
