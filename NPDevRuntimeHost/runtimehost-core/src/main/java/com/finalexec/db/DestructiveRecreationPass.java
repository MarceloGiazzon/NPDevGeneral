package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropColumn;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.NarrowType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

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
                    boolean requiredByModel = isColumnRequiredByModel(manifest, narrowType.table(), narrowType.column());
                    executeNarrowTypeDropAndRecreate(connection, narrowType.table(), narrowType.column(), narrowType.toType(), requiredByModel);
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
        String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.quotedIdentifier(column);
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " DROP COLUMN " + safeColumn)) {
            statement.executeUpdate();
        }
    }

    private static void executeDropTableCascade(Connection connection, String table) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        // Same CASCADE rationale as the whole-schema path (see executeWholeSchemaWipe): drops any
        // dependent FK constraint along with the table; it does not touch a referencing table's rows.
        try (PreparedStatement statement = connection.prepareStatement(
                "DROP TABLE IF EXISTS " + safeTable + " CASCADE")) {
            statement.executeUpdate();
        }
    }

    /** REG-61(a): does the model declare this column required (⇒ NOT NULL)? Looked up from the
     * manifest rather than carried on {@link NarrowType} itself, since that delta-item shape is
     * shared with migration-plan generation and impact reporting -- adding a field there would
     * ripple beyond this one consumer. Absent table/column (should not happen for an item this
     * pass is about to act on) is treated as not-required, matching the pre-fix behavior exactly. */
    private static boolean isColumnRequiredByModel(SchemaLifecycleExecutor.SchemaManifest manifest, String table, String column) {
        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
        DesiredTable desiredTable = desired.tables().get(table.toLowerCase(Locale.ROOT));
        if (desiredTable == null) {
            return false;
        }
        DesiredColumn desiredColumn = desiredTable.columns().get(column.toLowerCase(Locale.ROOT));
        return desiredColumn != null && !desiredColumn.nullable();
    }

    // Package-private (not private), matching TypeWideningPass#attemptInPlaceTypeWidenings's own
    // precedent, so it is directly unit-testable against a real H2 DataSource.
    static void executeNarrowTypeDropAndRecreate(Connection connection, String table, String column, String newType, boolean requiredByModel)
            throws SQLException {
        String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.quotedIdentifier(column);
        String safeType = TypeWideningPass.safeSqlType(newType);
        // REG-58: a plain DROP COLUMN fails when a unique index/constraint still references this
        // column (H2: "Column may be referenced by ..."; Postgres would refuse similarly). Every
        // ux_<table>_<column>-style index SchemaRealizationEmitter bootstraps is exactly this shape
        // for any field the model declares unique -- confirmed live on
        // identity_password_reset_tokens.token_hash, and several of the OTHER columns in this same
        // narrow-type batch (identity_users.username/email, produtos.codigo, ruas.codigo) are
        // equally likely unique-constrained business keys, so this is not a one-off. Not fixing the
        // OTHER side of the constraint lifecycle here deliberately: UniqueConstraintPass.
        // applyUniqueConstraints already runs on every boot's afterMigrate and re-adds any declared
        // constraint it finds missing (constraintExists returns false once the index backing it is
        // gone), so dropping the blocking index/constraint here is enough -- recreating it would be
        // redundant with, and could race, that existing idempotent pass.
        dropIndexesReferencingColumn(connection, table, safeTable, column);
        try (PreparedStatement drop = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " DROP COLUMN " + safeColumn)) {
            drop.executeUpdate();
        }
        // REG-61(a): re-apply NOT NULL directly when it is safe to -- a table with zero existing
        // rows can go straight to the model's declared required-ness, skipping the backfill dance
        // BackfillPass would otherwise demand on the very next boot for a column it sees as newly
        // nullable. A non-empty table cannot: adding a NOT NULL column with no default fails
        // outright against any existing row, so it is added nullable exactly as before, and
        // BackfillPass's existing TIGHTEN_NOT_NULL detection converges it once a default is
        // declared (or, for a UNIQUE-constrained column with more than one affected row, refuses
        // by name -- see REG-61(b)).
        String nullability = requiredByModel && isTableEmpty(connection, safeTable) ? " NOT NULL" : "";
        try (PreparedStatement add = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " ADD COLUMN " + safeColumn + " " + safeType + nullability)) {
            add.executeUpdate();
        }
    }

    /** {@code safeTable} is already {@link SchemaLifecycleExecutor#quotedIdentifier}-validated by every
     * caller before it reaches here -- never build this query from a raw, unvalidated identifier. */
    private static boolean isTableEmpty(Connection connection, String safeTable) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + safeTable);
                ResultSet resultSet = count.executeQuery()) {
            return resultSet.next() && resultSet.getLong(1) == 0L;
        }
    }

    /**
     * Finds every index (unique or not -- any index referencing the column would equally block the
     * DROP COLUMN below) that references {@code column} on {@code table}, via portable
     * {@link DatabaseMetaData#getIndexInfo} rather than assuming the {@code ux_<table>_<column>}
     * naming convention {@code UniqueConstraintPass} happens to use today -- a future naming change
     * or a hand-declared index would otherwise silently reopen this exact bug. Drops each one found;
     * a table can have more than one index touching the same column (composite + single-column), so
     * this collects distinct index names first rather than dropping mid-iteration over a live
     * {@code ResultSet}.
     */
    private static void dropIndexesReferencingColumn(Connection connection, String table, String safeTable, String column)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        LinkedHashSet<String> indexNames = new LinkedHashSet<>();
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getIndexInfo(null, null, candidate, false, false)) {
                while (resultSet.next()) {
                    String indexName = resultSet.getString("INDEX_NAME");
                    String indexColumn = resultSet.getString("COLUMN_NAME");
                    if (indexName != null && indexColumn != null && indexColumn.equalsIgnoreCase(column)) {
                        indexNames.add(indexName);
                    }
                }
            }
        }
        for (String indexName : indexNames) {
            String safeIndex = SchemaLifecycleExecutor.quotedIdentifier(indexName);
            try (PreparedStatement dropIndex = connection.prepareStatement("DROP INDEX IF EXISTS " + safeIndex)) {
                dropIndex.executeUpdate();
            } catch (SQLException indexDropFailed) {
                // A true named CONSTRAINT (not a plain index) needs ALTER TABLE ... DROP CONSTRAINT
                // instead of DROP INDEX on some engines -- fall back rather than fail the whole pass.
                try (PreparedStatement dropConstraint = connection.prepareStatement(
                        "ALTER TABLE " + safeTable + " DROP CONSTRAINT IF EXISTS " + safeIndex)) {
                    dropConstraint.executeUpdate();
                }
            }
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
    /**
     * STOR-16: the {@code Ephemeral} wipe. Drops every manifest-listed NPDev-owned table and lets
     * Flyway rebuild them from the model.
     *
     * <p>A SEPARATE method from {@link #executeWholeSchemaWipe}, not a flag on it, and the
     * difference is the point of the whole item. That method is the ACKNOWLEDGED path: it is
     * reached only after a diff has been computed, classified and itemized, it writes a history row
     * carrying the acknowledgment token that authorized it, and its own contract (LNCH-1 closeout
     * C1) is that the token is never null. None of that applies here. An {@code Ephemeral} app has
     * declared, at generation time and in writing, that its data does not survive a start -- so
     * there is nothing to diff, nothing to itemize, and no token to demand. Bolting a
     * "skip the token" flag onto the acknowledged path would have put the two policies one boolean
     * apart, which is exactly the shape STOR-16 rejected when it chose a fourth strategy over a
     * fifth boolean.
     *
     * <p>No pre-drop snapshot either: {@link SchemaDropSnapshotWriter} exists to preserve data
     * someone might want back, and snapshotting on every boot of an app whose data is declared
     * disposable would fill the disk with copies of exactly the thing being discarded.
     *
     * <p>Scoped to {@code manifest.businessTables() + manifest.internalTables()} -- the tables the
     * GENERATOR declared this app owns. A table someone else created in the same schema is never in
     * that list, so an app sharing a database cannot take its neighbour's tables with it. This is
     * what {@code scope: NpdevOwnedTablesOnly} means, enforced by construction rather than by a
     * string comparison.
     */
    static SchemaLifecycleExecutor.DestructiveRecreation executeEphemeralWipe(
            DataSource dataSource,
            SchemaLifecycleExecutor.SchemaManifest manifest
    ) {
        List<String> tables = new ArrayList<>();
        tables.addAll(manifest.businessTables());
        tables.addAll(manifest.internalTables());
        Collections.reverse(tables);

        List<String> dropped = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                if (table == null || table.isBlank()) {
                    continue;
                }
                // CASCADE for the same reason executeWholeSchemaWipe uses it: the manifest lists
                // tables in declaration order, which is not FK dependency order.
                String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
                try (PreparedStatement statement =
                             connection.prepareStatement("DROP TABLE IF EXISTS " + safeTable + " CASCADE")) {
                    statement.executeUpdate();
                    dropped.add(SchemaLifecycleExecutor.safeIdentifier(table));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed ephemeral schema recreation", exception);
        }
        // One unmissable line naming what was dropped. An app that silently empties its own database
        // every start is the kind of behaviour an operator must be able to see in the boot log
        // without knowing to look for it.
        System.out.println("NPDev schema lifecycle: EPHEMERAL -- this app discards its data on every "
                + "start (schemaLifecycle.strategy=Ephemeral). Dropped " + dropped.size()
                + " NPDev-owned table(s) and recreated them from the model: " + dropped);
        return new SchemaLifecycleExecutor.DestructiveRecreation(true, false, List.copyOf(dropped));
    }

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
                String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
                // CASCADE, not a precise FK-aware drop order: the manifest lists tables in
                // declaration order, which does not generally match the dependency order a
                // referencing table (e.g. notes.project_ref -> projects) requires -- dropping the
                // referenced table first throws ("depends on it") on both H2 and Postgres. CASCADE
                // drops the dependent FK constraint along with the table; it does not touch the
                // referencing table's ROWS (those are gone anyway, the referencing table is itself
                // in this same drop list during a full destructive recreate).
                try (PreparedStatement statement = connection.prepareStatement("DROP TABLE IF EXISTS " + safeTable + " CASCADE")) {
                    statement.executeUpdate();
                    // The REPORT gets the raw name: quoting is a SQL detail, and `dropped` is read
                    // by a human in the boot log.
                    dropped.add(SchemaLifecycleExecutor.safeIdentifier(table));
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
