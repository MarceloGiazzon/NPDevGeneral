package com.finalexec.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): the type-widening pass's plan derivation and DDL, split out of
 * {@link SchemaLifecycleExecutor} verbatim -- no behavior change.
 * {@link SchemaLifecycleExecutor#attemptInPlaceTypeWidenings} (which stays on the executor -- it is
 * directly unit-tested against a real H2 {@link DataSource}) calls straight into this sibling class.
 * Flat sibling in {@code com.finalexec.db}, not a subpackage -- see {@link TableRenamePass}'s class
 * javadoc for why.
 */
final class TypeWideningPass {

    private TypeWideningPass() {
    }

    /** The type-widening pass's plan derived from the canonical diff (SER-P4.5): the shared columns that
     * safely widen (each {@code {table, column, fromType}}) and the tables deferred whole to the
     * destructive path (per-table all-or-nothing: a table with ANY non-widening type change -- a
     * DESTRUCTIVE_NARROW_TYPE item -- widens nothing). */
    record WideningPlan(List<String[]> widened, Set<String> skippedTables) {
    }

    static WideningPlan wideningPlanFromDiff(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Map<String, List<String[]>> widenColsByTable = new LinkedHashMap<>(); // table -> [{column, fromType}]
        Set<String> narrowTables = new LinkedHashSet<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.SAFE_WIDEN) {
                widenColsByTable.computeIfAbsent(di.table(), t -> new ArrayList<>())
                        .add(new String[] {di.column(), di.before()});
            } else if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.DESTRUCTIVE_NARROW_TYPE) {
                narrowTables.add(di.table());
            }
        }
        // Every table with a type diff (widen and/or narrow); a narrow anywhere on it defers the whole table.
        Set<String> typeDiffTables = new LinkedHashSet<>(widenColsByTable.keySet());
        typeDiffTables.addAll(narrowTables);
        List<String[]> widened = new ArrayList<>();
        Set<String> skippedTables = new LinkedHashSet<>();
        for (String table : typeDiffTables) {
            if (narrowTables.contains(table)) {
                skippedTables.add(table);
            } else {
                for (String[] colFrom : widenColsByTable.getOrDefault(table, List.of())) {
                    widened.add(new String[] {table, colFrom[0], colFrom[1]});
                }
            }
        }
        return new WideningPlan(widened, skippedTables);
    }

    /**
     * Dialect-specific widen-column-type DDL (§6.1, confirmed against a real H2 instance before
     * being trusted here -- see {@code SchemaLifecycleExecutorTypeWideningIntegrationTest}):
     * Postgres uses {@code ALTER COLUMN ... TYPE}, H2 uses {@code ALTER COLUMN ... SET DATA TYPE}.
     * No {@code USING} clause is added for Postgres -- open question, not testable this session (no
     * Postgres instance available; see the phase evidence note) -- add one only if a real Postgres
     * run against one of the matrix's pairs proves it necessary.
     */
    static void executeWidenColumnType(Connection connection, String engine, String table, String column, String newType)
            throws SQLException {
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.safeIdentifier(column);
        String safeType = safeSqlType(newType);
        String sql = "Postgres".equals(engine)
                ? "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " TYPE " + safeType
                : "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " SET DATA TYPE " + safeType;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    /**
     * Guardrail 11's identifier-safety discipline, applied to the SQL TYPE portion of a widening
     * ALTER statement: a type string comes from the manifest, which is generator-controlled today
     * (a fixed {@code SqlTypeSupport} mapping) but is still author-adjacent input, not a literal
     * this class invented -- reject anything that isn't a bare word optionally followed by
     * {@code (n)} or {@code (p,s)}.
     */
    static String safeSqlType(String sqlType) {
        String value = sqlType == null ? "" : sqlType.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_ ]*(\\(\\d+(,\\s?\\d+)?\\))?")) {
            throw new IllegalStateException("Unsafe SQL type in schema realization manifest: " + sqlType);
        }
        return value;
    }
}
