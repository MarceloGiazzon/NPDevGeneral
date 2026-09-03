package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.kernel.storage.sql.PartialApplicationTruth;

import com.npdev.kernel.storage.sql.SqlDialects;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): the {@code npdev_schema_history} audit-row read/write
 * machinery and the {@code recordStepPass} write-before-execute helper, split out of
 * {@link SchemaLifecycleExecutor} verbatim -- no behavior change. Every method here was either
 * already {@code private} (and reachable only from methods that still live on the executor -- see
 * each call site) or, for {@code recordStepPass}, package-private and reused only from within this
 * same file's family of callers. Flat sibling in {@code com.finalexec.db}, not a subpackage -- see
 * {@link TableRenamePass}'s class javadoc for why.
 */
final class SchemaHistoryStore {

    /**
     * LNCH-1 Phase 4 (task 4.4). Self-bootstrapped exactly like {@code SchemaLifecycleExecutor}'s
     * {@code METADATA_TABLE} -- a plain {@code CREATE TABLE IF NOT EXISTS} this class issues itself.
     * Every fingerprint-mismatch pass through {@code beforeMigrate} -- safe (additive/rename/widening)
     * or destructive -- leaves exactly one row here.
     */
    private static final String HISTORY_TABLE = "npdev_schema_history";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SchemaHistoryStore() {
    }

    /** REG-8 Trigger C: {@code npdev_schema_history}'s {@code (to_fingerprint, applied_at_utc)} pair
     * for the most recent row matching a query -- either a specific target fingerprint or the whole
     * table. */
    record HistoryPoint(String toFingerprint, long appliedAtUtc) {
    }

    /**
     * REG-8 Trigger C (D4). Returns the history point that proves this database was migrated PAST
     * this build, or empty if nothing indicates that.
     *
     * <p>Deliberately NOT "does history contain a row for {@code stored} newer than THIS build's own
     * fingerprint" -- every ordinary forward upgrade would trip that (the current {@code stored}
     * value, by construction, always has a matching history row once any prior boot has gone through
     * the mismatch branch, INCLUDING a perfectly legitimate upgrade). The actual signal is narrower
     * and matches the register's own framing ("newer than what this build LAST WROTE"): has THIS
     * build's OWN target fingerprint ever been reached before (a row with {@code to_fingerprint =
     * manifest.schemaFingerprint()})? If never, this is a legitimate first-time deploy of this
     * fingerprint -- nothing to compare against, and Trigger C stays silent. If it HAS been reached
     * before, but a LATER row exists whose {@code to_fingerprint} differs, some other build has since
     * moved this exact database past the point this build itself last owned it.
     */
    static Optional<HistoryPoint> databaseMigratedPastThisBuild(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Optional<Long> lastReachedByThisBuild = latestOutcomeTimestamp(dataSource, manifest.schemaFingerprint());
        if (lastReachedByThisBuild.isEmpty()) {
            return Optional.empty();
        }
        Optional<HistoryPoint> latestOverall = latestOutcomeOverall(dataSource);
        if (latestOverall.isPresent()
                && latestOverall.get().appliedAtUtc() > lastReachedByThisBuild.get()
                && !manifest.schemaFingerprint().equals(latestOverall.get().toFingerprint())) {
            return latestOverall;
        }
        return Optional.empty();
    }

    /** {@code APPLIED}/{@code MANUALLY_MARKED_DONE} are the outcomes that represent a REAL, recorded
     * advance of this database's schema state -- as opposed to {@code REFUSED}/{@code PARTIAL-CRASH}
     * (nothing durably changed) or the {@code EXTERNAL_*} outcomes (REG-7.1's read-only ownership
     * mode, which never writes {@code npdev_schema_metadata} and is not part of this fingerprint-
     * pointer lifecycle at all). */
    private static Optional<Long> latestOutcomeTimestamp(DataSource dataSource, String toFingerprint) {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT applied_at_utc FROM " + HISTORY_TABLE + " WHERE to_fingerprint = ? AND outcome IN ("
                            + "'APPLIED', 'MANUALLY_MARKED_DONE') ORDER BY applied_at_utc DESC")) {
                statement.setString(1, toFingerprint);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private static Optional<HistoryPoint> latestOutcomeOverall(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT to_fingerprint, applied_at_utc FROM " + HISTORY_TABLE + " WHERE outcome IN ("
                            + "'APPLIED', 'MANUALLY_MARKED_DONE') ORDER BY applied_at_utc DESC")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(new HistoryPoint(resultSet.getString(1), resultSet.getLong(2)))
                            : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /** 3.2 (B4 migrate-only + progress-aware waiting): the most recent row in {@code npdev_schema_history},
     *  regardless of outcome -- unlike {@link #latestOutcomeOverall}, which filters to APPLIED/
     *  MANUALLY_MARKED_DONE ("did the fingerprint pointer really advance"), a waiter needs to see a
     *  row the instant it is written, before the pass finishes. {@link #recordStepPass} writes
     *  PARTIAL-CRASH BEFORE running its DDL and flips it to APPLIED only after, so a still-PARTIAL-CRASH
     *  latest row IS the "a pass is running right now" signal, and its {@code appliedAtUtc} is that
     *  pass's start time. */
    record RecentActivity(String stepName, String outcome, long recordedAtUtc) {
    }

    /** Read-only, and deliberately never calls {@link #ensureHistoryTable}: this is read from
     *  {@link MigrationMutex}'s WAIT loop, which can run before the boot currently holding the lock
     *  has ever called {@code flyway.migrate()}. Self-creating {@code npdev_schema_history} in that
     *  window would be a NEW REG-7.2 -- a WAITER, not the holder, poisoning Flyway's own "empty
     *  schema" check out from under the boot it is waiting on. A table that does not exist yet simply
     *  means no step pass has ever run against this database, which reads correctly as "no activity
     *  recorded" -- exactly what a first-ever boot looks like before its first rename/widen/backfill
     *  pass (there is nothing yet to diff against). Never throws: observability only, and must never
     *  affect whether -- or how long -- a boot waits for reasons of its own. */
    static Optional<RecentActivity> mostRecentActivity(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (!historyTableExists(connection)) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT classification, outcome, applied_at_utc FROM " + HISTORY_TABLE
                            + " ORDER BY applied_at_utc DESC")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(new RecentActivity(
                                    resultSet.getString(1), resultSet.getString(2), resultSet.getLong(3)))
                            : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private static boolean historyTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().tableExistsInCurrentSchemaSql(HISTORY_TABLE))) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    /**
     * LNCH-1 Phase 4 (task 4.4). Idempotent, self-bootstrapped exactly like {@code METADATA_TABLE}
     * -- called at the top of every history write so a fresh app (no prior destructive/rename/
     * widening pass) still gets the table before its first row.
     */
    private static void ensureHistoryTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateTable(HISTORY_TABLE,
                        "CREATE TABLE " + HISTORY_TABLE
                        + " (id " + InternalDdlTypes.keyText() + " PRIMARY KEY, "
                        + "applied_at_utc BIGINT NOT NULL, "
                        + "from_fingerprint " + InternalDdlTypes.text() + ", "
                        + "to_fingerprint " + InternalDdlTypes.text() + ", "
                        + "classification " + InternalDdlTypes.text() + ", "
                        + "items_json " + InternalDdlTypes.text() + ", "
                        + "ack_token_used " + InternalDdlTypes.text() + ", "
                        + "outcome " + InternalDdlTypes.text() + " NOT NULL)")
        )) {
            statement.executeUpdate();
        }
    }

    /**
     * Every destructive item's {@link SchemaDeltaItem#displayString()}, JSON-serialized as a
     * plain array of strings -- already in {@link SchemaDeltaReport}'s deterministic sorted order,
     * so this column's content is itself order-independent for the same underlying diff. Uses the
     * DISPLAY form (not the hashed stable string) so a {@code DROP_TABLE} row keeps its human-facing
     * row-count metadata in {@code items_json}, even though that count is out of the ack-token hash
     * (LNCH-1 remediation F2).
     */
    private static String itemsJson(SchemaDeltaReport report) {
        return itemsJson(report == null ? List.of() : report.displayStrings());
    }

    // itemsJson(List<String>) -- the overload this delegates to -- already exists below, added for
    // recordStepPass's own write-before-execute rows (LNCH-1 remediation R4/F5); reused as-is here
    // rather than duplicated.

    /**
     * The single, shared INSERT used by every history-row writer below. A broken write is caught
     * and logged here, never propagated -- a history-table failure (unreachable metadata table,
     * disk full) must never mask or replace the actual migration outcome (a thrown refusal, or a
     * successfully-applied change) -- "if the metadata table is reachable" per the plan.
     *
     * @return the row's generated id, or {@code null} if the write itself failed (callers must
     *         treat a {@code null} id as "there is no row to later update").
     */
    private static String insertHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed,
            String outcome
    ) {
        return insertHistoryRowCore(dataSource, fromFingerprint, toFingerprint, classification,
                itemsJson(report), ackTokenUsed, outcome);
    }

    /** B5-B (boundary-lift 2026-09-02, package 4.1): same INSERT, for a caller (ReverseMigrationPlanner)
     *  that diffs via the newer {@code SchemaDiffEngine}/{@code SchemaDiffItem} vocabulary (SER Phase 2)
     *  rather than the older {@link SchemaDeltaReport}/{@code SchemaDeltaItem} ladder
     *  {@link SchemaDeltaReport#generate} itself only builds from a live introspection in the forward
     *  direction -- so it has a pre-built display-string list to record, never a {@link SchemaDeltaReport}. */
    private static String insertHistoryRowWithItems(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            List<String> displayStrings,
            String ackTokenUsed,
            String outcome
    ) {
        return insertHistoryRowCore(dataSource, fromFingerprint, toFingerprint, classification,
                itemsJson(displayStrings), ackTokenUsed, outcome);
    }

    private static String insertHistoryRowCore(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            String itemsJsonValue,
            String ackTokenUsed,
            String outcome
    ) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + HISTORY_TABLE + " (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, fromFingerprint);
                statement.setString(4, toFingerprint);
                statement.setString(5, classification == null ? null : classification.name());
                statement.setString(6, itemsJsonValue);
                if (ackTokenUsed == null || ackTokenUsed.isBlank()) {
                    statement.setNull(7, Types.VARCHAR);
                } else {
                    statement.setString(7, ackTokenUsed);
                }
                statement.setString(8, outcome);
                statement.executeUpdate();
            }
            return id;
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: failed writing npdev_schema_history row (continuing -- "
                    + "a broken history write must never block or mask the actual migration outcome): "
                    + exception.getMessage());
            return null;
        }
    }

    /** REFUSED / arbitrary-outcome one-shot write (no later update). Used by refusals ("nothing
     * was attempted, so INSERT directly with outcome = REFUSED", per the plan) and by the safe
     * (additive/rename/widening) paths, where write-then-immediately-mark-applied is fine since
     * those steps are individually idempotent-by-check -- no crash-window concern. */
    static void writeHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed,
            String outcome
    ) {
        insertHistoryRow(dataSource, fromFingerprint, toFingerprint, classification, report, ackTokenUsed, outcome);
    }

    /** Safe-path (SAFE_ADDITIVE / RENAME_DETECTED / TYPE_CHANGE_DETECTED-resolved-by-widening)
     * history row: no destructive items to report (an empty items list), no acknowledgment token,
     * outcome APPLIED directly -- see {@link #writeHistoryRow}'s javadoc for why a single INSERT is
     * sufficient here. */
    static void writeAppliedHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaLifecycleExecutor.SchemaChangeClassification classification
    ) {
        insertHistoryRow(dataSource, fromFingerprint, toFingerprint, classification, null, null, "APPLIED");
    }

    /** Destructive-path PENDING write ("write-before-execute", §2.4): inserted with
     * {@code outcome = 'PARTIAL-CRASH'} before any DDL runs. */
    static String insertPendingHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed
    ) {
        return insertHistoryRow(dataSource, fromFingerprint, toFingerprint, classification, report, ackTokenUsed, "PARTIAL-CRASH");
    }

    /** B5-B (boundary-lift 2026-09-02, package 4.1): the reverse-migration counterpart of
     *  {@link #insertPendingHistoryRow} -- same write-before-execute PENDING contract, for a caller
     *  with a pre-built display-string list instead of a {@link SchemaDeltaReport} (see
     *  {@link #insertHistoryRowWithItems}'s javadoc). {@link #markHistoryRowApplied} is reused
     *  unchanged to flip this row to APPLIED -- it only needs the row id, not how it was built. */
    static String insertPendingHistoryRowWithItems(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaLifecycleExecutor.SchemaChangeClassification classification,
            List<String> displayStrings,
            String ackTokenUsed
    ) {
        return insertHistoryRowWithItems(dataSource, fromFingerprint, toFingerprint, classification, displayStrings, ackTokenUsed, "PARTIAL-CRASH");
    }

    /** Destructive-path "update-after" (§2.4): flips a PARTIAL-CRASH row to APPLIED once every
     * item in the pass has executed successfully. A {@code null} id (the pending insert itself
     * failed) is a safe no-op -- there is no row to update. */
    static void markHistoryRowApplied(DataSource dataSource, String historyId) {
        if (historyId == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + HISTORY_TABLE + " SET outcome = ? WHERE id = ?")) {
            statement.setString(1, "APPLIED");
            statement.setString(2, historyId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            System.out.println("NPDev schema lifecycle: failed updating npdev_schema_history outcome to APPLIED "
                    + "for row " + historyId + " (the DDL itself already succeeded -- only the audit row write "
                    + "failed): " + exception.getMessage());
        }
    }

    /** A DDL action that may throw {@link SQLException}, for {@link #recordStepPass}. */
    @FunctionalInterface
    interface SqlRunnable {
        void run() throws SQLException;
    }

    /** {@code items_json} for a plain list of human-readable step-item strings, rather than a
     * {@link SchemaDeltaReport} -- originally for the per-pass write-before-execute rows
     * ({@link #recordStepPass}, LNCH-1 remediation R4/F5), now shared by
     * {@link #insertHistoryRowWithItems} (B5-B, boundary-lift 2026-09-02 package 4.1) for the same
     * reason: a caller with a pre-built display-string list instead of a {@link SchemaDeltaReport}. */
    private static String itemsJson(List<String> itemDetails) {
        try {
            return OBJECT_MAPPER.writeValueAsString(itemDetails == null ? List.of() : itemDetails);
        } catch (Exception exception) {
            return "[]";
        }
    }

    /**
     * LNCH-1 remediation R4 (F5): write-before-execute history for a single mutating PASS (a batch of
     * renames/relaxations/widenings/backfills). Semantics per plan §2.4: if {@code itemDetails} is
     * empty, run and write NOTHING (no noise rows on no-op boots); otherwise insert one
     * {@code PARTIAL-CRASH} row (classification = {@code stepName}, {@code items_json} = the item
     * detail list) BEFORE running the DDL, then flip it to {@code APPLIED} after every item executes.
     * A crash mid-pass leaves the row at {@code PARTIAL-CRASH} -- an accurate record that this pass
     * did not finish. The from-fingerprint is read live (still the pre-boot value at this point, since
     * {@code afterMigrate} writes the new one only at the very end).
     */
    static void recordStepPass(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest, String stepName,
            List<String> itemDetails, SqlRunnable ddl) throws SQLException {
        if (itemDetails == null || itemDetails.isEmpty()) {
            return;
        }
        String from = SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource);
        String historyId = insertStepPendingRow(dataSource, from, manifest.schemaFingerprint(), stepName, itemDetails);
        try {
            ddl.run();
        } catch (SQLException failure) {
            // storage/FULL_SUPPORT_PLAN.md W3, and the direct continuation of STOR-2.
            //
            // The PARTIAL-CRASH row above is already an accurate machine record. What was missing is
            // the SENTENCE: this method used to let the raw SQLException propagate, and every caller
            // wraps it as "Failed relaxing no-longer-required column(s)" -- true, and silent about
            // the thing that decides the operator's next move.
            //
            // On Postgres/SQL Server the pass rolls back and re-running is correct. On MySQL and H2
            // DDL COMMITS IMPLICITLY, so every item before the failure is ALREADY PERMANENT and the
            // database is in a state neither model describes. Those two situations call for opposite
            // actions, and until now the message did not distinguish them at all. That is the same
            // false-all-clear shape as STOR-2, one layer down: the half-applied migration is the only
            // storage failure that corrupts instead of failing loudly.
            //
            // Behaviour is deliberately unchanged -- the exception still propagates, the boot still
            // refuses. Only the claim is corrected.
            throw new SQLException(
                    PartialApplicationTruth.afterFailedMultiStep(stepName, itemDetails, failedIndexOf(itemDetails, failure))
                    + " History row: " + (historyId == null ? "(not written)" : historyId)
                    + " (outcome PARTIAL-CRASH in " + HISTORY_TABLE + ").",
                    failure.getSQLState(), failure.getErrorCode(), failure);
        }
        markHistoryRowApplied(dataSource, historyId);
    }

    /**
     * Which item threw, when the runnable is a loop this class cannot see inside.
     *
     * <p>Callers pass ONE lambda that iterates their own plan, so there is no per-item hook to count
     * from. The engine's own error text names the object it failed on, and matching an item against
     * it recovers the index in the common case.
     *
     * <p><b>Returns -1 rather than guessing.</b> An index this method is not sure of would put a
     * specific, wrong list of "already permanent" items in front of an operator during the one
     * failure where they are about to act on it -- strictly worse than saying the item is unknown.
     */
    private static int failedIndexOf(List<String> itemDetails, SQLException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return -1;
        }
        String haystack = message.toLowerCase(java.util.Locale.ROOT);
        int match = -1;
        for (int index = 0; index < itemDetails.size(); index++) {
            // Item details read "RELAX_NOT_NULL <table>.<column>"; the identifier is the part an
            // engine error would echo.
            String[] words = itemDetails.get(index).toLowerCase(java.util.Locale.ROOT).split("\\s+");
            String identifier = words[words.length - 1];
            if (identifier.length() >= 3 && haystack.contains(identifier)) {
                if (match >= 0) {
                    return -1; // two items match the same error text -- do not guess between them
                }
                match = index;
            }
        }
        return match;
    }

    /** Inserts a {@code PARTIAL-CRASH} history row carrying a raw step name (classification) and a
     * raw item-detail list (items_json), for {@link #recordStepPass}. Follows {@link #insertHistoryRow}'s
     * broken-write-never-propagates discipline: a failed audit write returns {@code null} (a safe
     * no-op for the later {@link #markHistoryRowApplied}) and never blocks the DDL it records. */
    private static String insertStepPendingRow(DataSource dataSource, String fromFingerprint,
            String toFingerprint, String stepName, List<String> itemDetails) {
        return insertRawHistoryRow(dataSource, fromFingerprint, toFingerprint, stepName, itemDetails, "PARTIAL-CRASH");
    }

    /** Like {@link #insertHistoryRow} but writes a RAW classification string (a step name or a
     * pre-check label, not a {@link SchemaLifecycleExecutor.SchemaChangeClassification} enum) and a
     * raw item-detail list -- used by {@link #recordStepPass} (PARTIAL-CRASH) and by the
     * unique-precheck refusal (REFUSED), both LNCH-1 remediation R4 / F5. Same
     * broken-write-never-propagates discipline. */
    static String insertRawHistoryRow(DataSource dataSource, String fromFingerprint,
            String toFingerprint, String classificationText, List<String> itemDetails, String outcome) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + HISTORY_TABLE + " (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, fromFingerprint);
                statement.setString(4, toFingerprint);
                statement.setString(5, classificationText);
                statement.setString(6, itemsJson(itemDetails));
                statement.setNull(7, Types.VARCHAR);
                statement.setString(8, outcome);
                statement.executeUpdate();
            }
            return id;
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: failed writing npdev_schema_history detail row (continuing -- "
                    + "a broken history write must never block the actual migration): " + exception.getMessage());
            return null;
        }
    }
}
