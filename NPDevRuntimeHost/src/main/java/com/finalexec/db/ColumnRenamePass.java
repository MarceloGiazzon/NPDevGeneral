package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.RenameResolution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): the column-rename pass's plan derivation and DDL, split out of
 * {@link SchemaLifecycleExecutor} verbatim -- no behavior change.
 * {@link SchemaLifecycleExecutor#attemptInPlaceRenames} (which stays on the executor -- it is directly
 * unit-tested against a real H2 {@link DataSource}) calls straight into this sibling class. Flat
 * sibling in {@code com.finalexec.db}, not a subpackage -- see {@link TableRenamePass}'s class javadoc
 * for why.
 */
final class ColumnRenamePass {

    private ColumnRenamePass() {
    }

    /** The column-rename pass's whole plan, derived from the canonical diff (SER-P4.4). */
    record ColumnRenamePlan(List<String[]> renames, List<String> skipped, List<String> staleWarnings) {
    }

    /**
     * SER-P4.4: the column-rename plan derived from the canonical {@link com.finalexec.db.schemastate.SchemaDiff}
     * instead of a second live introspection + {@link RenameResolution} pass.
     * <ul>
     *   <li><b>renames</b> ({@code {table, old, new}}) -- the {@code RENAME_COLUMN} items the engine
     *       resolves, on tables that pass the SAME per-table eligibility gate the bespoke pass applied (a
     *       table whose remaining missing columns are not all additive-eligible is deferred whole to the
     *       destructive path, so none of its renames are applied here). Applying an eligible rename is
     *       unconditional even when the column ALSO has a type change: {@code beforeMigrate} runs
     *       {@code attemptInPlaceTypeWidenings} immediately afterward against the new name, and a residual
     *       narrowing simply re-classifies the table onto the destructive path (whose pre-drop snapshot
     *       captures data under the already-renamed column) -- no incorrect persisted state.</li>
     *   <li><b>skipped</b> -- those ineligible tables, for the operator log.</li>
     *   <li><b>staleWarnings</b> -- R6 (F7): a declared rename whose OLD and NEW columns are BOTH absent
     *       live explained nothing (a stale {@code renamedFrom} marker can turn a rename into a drop).</li>
     * </ul>
     */
    static ColumnRenamePlan columnRenamesFromDiff(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        // Per table: the resolved renames (old->new) and the remaining-missing (added) columns. A
        // RENAME_COLUMN item is the rename; an ADD_(REQUIRED_)COLUMN item is a column absent live and not
        // rename-explained -- exactly the bespoke pass's remainingMissing.
        Map<String, List<String[]>> renamesByTable = new LinkedHashMap<>();
        Map<String, Set<String>> missingByTable = new LinkedHashMap<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.SAFE_RENAME
                    && di.itemKey().startsWith("RENAME_COLUMN:")) {
                renamesByTable.computeIfAbsent(di.table(), t -> new ArrayList<>())
                        .add(new String[] {di.before(), di.after()});
            } else if (di.itemKey().startsWith("ADD_COLUMN:") || di.itemKey().startsWith("ADD_REQUIRED_COLUMN:")) {
                missingByTable.computeIfAbsent(di.table(), t -> new LinkedHashSet<>()).add(di.column());
            }
        }

        List<String[]> renames = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> entry : renamesByTable.entrySet()) {
            String table = entry.getKey();
            Set<String> additive = new LinkedHashSet<>(
                    manifest.businessTableAdditiveColumns().getOrDefault(table, List.of()));
            Set<String> remainingMissing = missingByTable.getOrDefault(table, Set.of());
            if (!additive.containsAll(remainingMissing)) {
                skipped.add(table + " (a remaining expected column is neither renamed-in nor "
                        + "additive-eligible -- remainingMissing=" + remainingMissing + ")");
                continue;
            }
            for (String[] oldNew : entry.getValue()) {
                renames.add(new String[] {table, oldNew[0], oldNew[1]});
            }
        }

        // R6 (F7): the stale-marker warning, now checked against the live CurrentSchema (the full column
        // set the diff read) rather than a separate readActualColumns call.
        List<String> staleWarnings = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> tableRenames : manifest.businessTableRenamedColumns().entrySet()) {
            String table = tableRenames.getKey();
            Map<String, String> declaredRenames = tableRenames.getValue();
            if (declaredRenames.isEmpty()
                    || manifest.businessTableColumns().getOrDefault(table, List.of()).isEmpty()) {
                continue;
            }
            com.finalexec.db.schemastate.CurrentTable liveTable = current.tables().get(table.toLowerCase(Locale.ROOT));
            Set<String> liveColumns = liveTable == null ? Set.of() : liveTable.columns().keySet();
            if (liveColumns.isEmpty()) {
                continue; // brand-new table -- nothing live to rename
            }
            for (Map.Entry<String, String> declared : declaredRenames.entrySet()) {
                String newName = declared.getKey();
                String oldName = declared.getValue();
                if (oldName != null && !oldName.isBlank()
                        && !liveColumns.contains(oldName.toLowerCase(Locale.ROOT))
                        && !liveColumns.contains(newName.toLowerCase(Locale.ROOT))) {
                    staleWarnings.add("NPDev schema lifecycle: WARNING -- declared rename '" + oldName
                            + "' -> '" + newName + "' on table '" + table + "' explains nothing: neither the "
                            + "old nor the new column exists live. A stale renamedFrom marker (e.g. a second "
                            + "rename that never updated the marker to the immediately-previous name) can turn "
                            + "a rename into a destructive drop -- see docs/SCHEMA_EVOLUTION.md#marker-lifecycle.");
                }
            }
        }
        return new ColumnRenamePlan(renames, skipped, staleWarnings);
    }

    /**
     * Rename-column DDL, from the dialect.
     *
     * <p>This javadoc used to say {@code manifest.engine()} "is one of exactly InMemory, H2Local,
     * H2Server, Postgres" and the code was a two-way {@code "Postgres".equals(engine) ? ... : ...}.
     * Both stopped being true when MySQL and SQL Server became selectable, and nothing said so --
     * MySQL silently got H2's spelling, which it does not accept (STOR-10):
     *
     * <pre>
     *   schema pass 'COLUMN_RENAME' failed at RENAME_COLUMN books.isbn -&gt; isbn13.
     *   Engine 'mysql' COMMITS IMPLICITLY ON DDL, so this pass is HALF APPLIED
     * </pre>
     *
     * <p>SQL Server does not use {@code ALTER TABLE} for this at all ({@code sp_rename}), so the
     * shape could not be a two-way choice even in principle. {@code engine} is kept as a parameter
     * for the caller's error messages; the SQL itself now comes from {@link SqlDialect#renameColumn}.
     */
    static void executeRenameColumn(Connection connection, String engine, String table, String oldName, String newName)
            throws SQLException {
        // RAW on purpose. renameColumn quotes inside the dialect (STOR-6), because SQL Server's
        // sp_rename takes both names in a string LITERAL while the other three compose real syntax --
        // a decision only the dialect can make.
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeOld = SchemaLifecycleExecutor.safeIdentifier(oldName);
        String safeNew = SchemaLifecycleExecutor.safeIdentifier(newName);
        String sql = com.npdev.kernel.storage.sql.SqlDialects.forConnection(connection)
                .renameColumn(safeTable, safeOld, safeNew);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
