package com.finalexec.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.4's corrected
 * text): the table-rename pass's diff-derivation and DDL, split out of {@link SchemaLifecycleExecutor}
 * verbatim -- no behavior change. {@link SchemaLifecycleExecutor#attemptInPlaceTableRenames} (which
 * stays on the executor -- it is directly unit-tested against a real H2 {@link DataSource}) calls
 * straight into this sibling class, exactly the way {@code DesiredSchemaFactory}/{@code SchemaDeltaReport}
 * already call back into the executor's package-private statics (this file deliberately stays a flat
 * sibling in {@code com.finalexec.db}, not a subpackage, for the same reason {@code DesiredSchemaFactory}
 * documents: {@link SchemaLifecycleExecutor}'s helpers are package-private, and Java sub-packages get no
 * special access to them).
 */
final class TableRenamePass {

    private TableRenamePass() {
    }

    /** SER-P4.3: the table-rename work-list (new -&gt; old) derived from the canonical {@link
     * com.finalexec.db.schemastate.SchemaDiff} -- the {@code RENAME_TABLE} items the engine resolves.
     * Proven equal to the bespoke {@link com.npdev.dsl.v1.schemaevolution.RenameResolution} result
     * before it replaces it. */
    static Map<String, String> tableRenamesFromDiff(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Map<String, String> renames = new LinkedHashMap<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.SAFE_RENAME
                    && di.itemKey().startsWith("RENAME_TABLE:")) {
                renames.put(di.after(), di.before()); // after = new name, before = old name
            }
        }
        return renames;
    }

    /**
     * Table-rename DDL (§6.1): {@code ALTER TABLE ... RENAME TO ...} is identical on both Postgres
     * and H2 (unlike column rename, which differs per engine) -- confirmed via the real H2
     * integration test {@code SchemaLifecycleExecutorTableRenameTest} before being trusted here.
     */
    static void executeRenameTable(Connection connection, String oldTable, String newTable) throws SQLException {
        String safeOld = SchemaLifecycleExecutor.safeIdentifier(oldTable);
        String safeNew = SchemaLifecycleExecutor.safeIdentifier(newTable);
        String sql = "ALTER TABLE " + safeOld + " RENAME TO " + safeNew;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
