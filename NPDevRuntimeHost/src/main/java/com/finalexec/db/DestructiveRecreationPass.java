package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropColumn;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.NarrowType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): the destructive-recreation pass (the itemized surgical path
 * and the whole-schema-wipe fallback), the pending-acknowledgment consume helper, and the
 * "agreement check" refusal-message enrichment, split out of {@link SchemaLifecycleExecutor}
 * verbatim -- no behavior change. All of these were {@code private} and reachable only from
 * {@code beforeMigrateDecision} (which stays on the executor -- it writes the
 * {@code conversionHooksAppliedLastDecision} instance field and is the core orchestrator this split
 * deliberately leaves alone), so they move here in full rather than leaving a wrapper. Flat sibling
 * in {@code com.finalexec.db}, not a subpackage -- see {@link TableRenamePass}'s class javadoc for
 * why.
 */
final class DestructiveRecreationPass {

    private DestructiveRecreationPass() {
    }

    /** Consume-on-use (see {@link PendingSchemaAcknowledgmentStore}'s class javadoc): only called
     * AFTER a destructive pass has fully applied, never before -- so a crash mid-destructive still
     * finds the same pending row available to authorize a retry on the next boot. A {@code null}
     * acknowledgment (the static manifest field alone authorized this pass, or the deprecated
     * blanket flag did) is a no-op. */
    static void consumePendingAcknowledgmentIfAny(
            DataSource dataSource, PendingSchemaAcknowledgmentStore.PendingAcknowledgment pendingAcknowledgment
    ) {
        if (pendingAcknowledgment != null) {
            PendingSchemaAcknowledgmentStore.consume(dataSource, pendingAcknowledgment.id());
        }
    }

    /**
     * LNCH-1 Phase 6 (task 6.3): the "friendlier" agreement-check enrichment. The token-mismatch
     * refusal above already correctly blocks regardless -- this method changes nothing about that
     * decision, it only enriches the REFUSAL MESSAGE when there is something more specific to say.
     * {@code manifest.planItemStableStrings()} is the destructive-item stable strings a migration
     * plan computed at GENERATION time (see {@code MigrationPlanEmitter}, populated only when a
     * future {@code Build-NpdevApp.ps1 -PlanOnly}/{@code -Upgrade} passed
     * {@code --schemaMigrationPlanOut}; empty for every app generated without that flag). When it is
     * non-empty AND differs from what THIS report independently found live at BOOT time, an operator
     * is very likely looking at model/database drift since the plan was generated (the classic stale-
     * artifact problem) -- printing both lists side by side turns an opaque "wrong token" refusal
     * into "here is exactly what changed out from under your plan." When the two lists already agree
     * (or no plan-derived list is available at all -- the common case today, since Phase 6's CLI
     * wiring is opt-in), this returns {@code ""} and the refusal message is unchanged from Phase 4.
     */
    static String agreementCheckSuffix(SchemaLifecycleExecutor.SchemaManifest manifest, SchemaDeltaReport report) {
        List<String> planned = manifest.planItemStableStrings();
        if (planned == null || planned.isEmpty()) {
            return "";
        }
        List<String> plannedSorted = new ArrayList<>(planned);
        Collections.sort(plannedSorted);
        List<String> actualSorted = new ArrayList<>(report.stableStrings());
        Collections.sort(actualSorted);
        if (plannedSorted.equals(actualSorted)) {
            return "";
        }
        return " NOTE (LNCH-1 Phase 6 agreement check): the migration plan reviewed/acknowledged at "
                + "generation time expected these destructive item(s): " + plannedSorted
                + " -- but the live database at boot actually needs: " + actualSorted
                + ". This usually means the model was edited again after the plan was generated, or the "
                + "target database has drifted from the state the plan assumed. Regenerate the plan "
                + "against the current model and database before acknowledging.";
    }

    /**
     * LNCH-1 Phase 4 (task 4.3): the NEW default destructive path -- executes ONLY the tables/
     * columns the itemized {@link SchemaDeltaReport} actually names, gated on a matching
     * {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken}. Snapshots only the affected
     * tables (§2.6 answer 1) via the existing {@link SchemaDropSnapshotWriter#snapshotBeforeDrop}
     * -- no new "table-subset" overload needed, it already accepts an arbitrary table list.
     *
     * <p>Write-before-execute, update-after (§2.4 crash semantics): the history row is inserted
     * with {@code outcome = 'PARTIAL-CRASH'} BEFORE any DDL runs, and only updated to
     * {@code 'APPLIED'} after every item has executed successfully. If the JVM dies mid-loop, the
     * row is left exactly as inserted -- an accurate record that this pass crashed partway
     * through, not a false claim either way.
     */
    static SchemaLifecycleExecutor.DestructiveRecreation executeSurgicalDestruction(
            DataSource dataSource,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            String stored,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed
    ) {
        List<String> affectedTables = new ArrayList<>(report.affectedTables());
        Collections.sort(affectedTables);
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, affectedTables);

        String historyId = SchemaHistoryStore.insertPendingHistoryRow(dataSource, stored, manifest.schemaFingerprint(),
                classification, report, ackTokenUsed);

        List<String> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (SchemaDeltaItem item : report.items()) {
                if (item instanceof DropColumn dropColumn) {
                    executeDropColumn(connection, dropColumn.table(), dropColumn.column());
                    applied.add("DROP_COLUMN " + dropColumn.table() + "." + dropColumn.column());
                } else if (item instanceof DropTable dropTable) {
                    executeDropTableCascade(connection, dropTable.table());
                    applied.add("DROP_TABLE " + dropTable.table());
                } else if (item instanceof NarrowType narrowType) {
                    // Drop-and-recreate, not a casting ALTER COLUMN TYPE: per the plan, data in a
                    // narrowed column is acknowledged lost by the token, and a cast can fail
                    // per-row (e.g. a too-long VARCHAR value) -- simpler and more honest to drop
                    // and recreate empty than attempt a partially-successful cast.
                    executeNarrowTypeDropAndRecreate(connection, narrowType.table(), narrowType.column(), narrowType.toType());
                    applied.add("NARROW_TYPE " + narrowType.table() + "." + narrowType.column() + " -> " + narrowType.toType());
                }
                // UNKNOWN items never reach here -- the caller only takes this path when
                // report.hasOnlyNamedDestructiveKinds() is true.
            }
        } catch (SQLException exception) {
            // Deliberately NOT updating the history row here -- it stays at PARTIAL-CRASH, which is
            // the correct, honest record of a half-applied surgical pass (§2.4).
            throw new IllegalStateException("Failed applying surgical destructive schema changes ("
                    + applied.size() + "/" + report.items().size() + " item(s) applied before failure: "
                    + applied + ")", exception);
        }
        SchemaHistoryStore.markHistoryRowApplied(dataSource, historyId);
        System.out.println("NPDev schema lifecycle: surgical destructive changes applied: " + applied);
        return new SchemaLifecycleExecutor.DestructiveRecreation(true, false, List.copyOf(affectedTables));
    }

    private static void executeDropColumn(Connection connection, String table, String column) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.safeIdentifier(column);
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " DROP COLUMN " + safeColumn)) {
            statement.executeUpdate();
        }
    }

    private static void executeDropTableCascade(Connection connection, String table) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        // Same CASCADE rationale as the whole-schema path (see executeWholeSchemaWipe): drops any
        // dependent FK constraint along with the table; it does not touch a referencing table's rows.
        try (PreparedStatement statement = connection.prepareStatement(
                "DROP TABLE IF EXISTS " + safeTable + " CASCADE")) {
            statement.executeUpdate();
        }
    }

    private static void executeNarrowTypeDropAndRecreate(Connection connection, String table, String column, String newType)
            throws SQLException {
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.safeIdentifier(column);
        String safeType = TypeWideningPass.safeSqlType(newType);
        try (PreparedStatement drop = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " DROP COLUMN " + safeColumn)) {
            drop.executeUpdate();
        }
        try (PreparedStatement add = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " ADD COLUMN " + safeColumn + " " + safeType)) {
            add.executeUpdate();
        }
    }

    /**
     * The OLD destructive path (pre-Phase-4 behavior, unchanged DDL), now reached ONLY when the
     * residual diff includes an {@code UNKNOWN} item the surgical path cannot explain. Same
     * write-before-execute/update-after history lifecycle as the surgical path.
     *
     * <p><b>LNCH-1 hardening X1 (finding X-B1):</b> this path used to ALSO be reached whenever
     * authorization came solely from the deprecated blanket {@code destructiveAllowed} flag, even
     * when every item in the report was surgically executable. That was a critical regression: this
     * method drops the tables the NEW manifest lists, so a dropped concept's orphaned table survived
     * while every still-modelled concept's data was destroyed. The authorization source no longer
     * influences which execution path runs -- only the presence of an {@code UNKNOWN} item does.
     *
     * <p><b>LNCH-1 closeout C1 (finding C-B1):</b> the authorization source no longer selects the
     * path, but it does still gate it. Because this method destroys EVERY manifest-listed table's
     * data, it now requires the itemized acknowledgment token exactly as a concept drop does -- the
     * blanket flag alone is refused before we get here. Callers may therefore rely on
     * {@code acknowledgmentToken} being non-null.
     */
    static SchemaLifecycleExecutor.DestructiveRecreation executeWholeSchemaWipe(
            DataSource dataSource,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            String stored,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed
    ) {
        List<String> tables = new ArrayList<>();
        tables.addAll(manifest.businessTables());
        tables.addAll(manifest.internalTables());
        Collections.reverse(tables);
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, tables);

        String historyId = SchemaHistoryStore.insertPendingHistoryRow(dataSource, stored, manifest.schemaFingerprint(),
                classification, report, ackTokenUsed);

        List<String> dropped = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                if (table == null || table.isBlank()) {
                    continue;
                }
                String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
                // CASCADE, not a precise FK-aware drop order: the manifest lists tables in
                // declaration order, which does not generally match the dependency order a
                // referencing table (e.g. notes.project_ref -> projects) requires -- dropping the
                // referenced table first throws ("depends on it") on both H2 and Postgres. CASCADE
                // drops the dependent FK constraint along with the table; it does not touch the
                // referencing table's ROWS (those are gone anyway, the referencing table is itself
                // in this same drop list during a full destructive recreate).
                try (PreparedStatement statement = connection.prepareStatement("DROP TABLE IF EXISTS " + safeTable + " CASCADE")) {
                    statement.executeUpdate();
                    dropped.add(safeTable);
                }
            }
            System.out.println("NPDev destructive schema recreation dropped manifest-listed NPDev-owned tables: " + dropped);
            System.out.println("NPDev destructive schema recreation stored fingerprint: " + stored);
            System.out.println("NPDev destructive schema recreation generated fingerprint: " + manifest.schemaFingerprint());
        } catch (SQLException exception) {
            // History row deliberately left at PARTIAL-CRASH -- see executeSurgicalDestruction's note.
            throw new IllegalStateException("Failed destructive schema recreation", exception);
        }
        SchemaHistoryStore.markHistoryRowApplied(dataSource, historyId);
        return new SchemaLifecycleExecutor.DestructiveRecreation(true, false, List.copyOf(dropped));
    }
}
