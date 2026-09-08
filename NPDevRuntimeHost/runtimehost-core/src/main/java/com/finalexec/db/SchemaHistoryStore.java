package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.kernel.storage.sql.PartialApplicationTruth;

import com.npdev.kernel.storage.sql.SqlDialects;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** REG-8 Trigger C: {@code npdev_schema_history}'s most recent row for a query -- either a
     *  specific target fingerprint or the whole table. QUAL-55 added {@code seq}: nullable, because a
     *  row written before the column existed (or by an older jar) has none. */
    record HistoryPoint(String toFingerprint, long appliedAtUtc, Long seq) {
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
    static Optional<HistoryPoint> databaseMigratedPastThisBuild(
            DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Optional<HistoryPoint> lastReachedByThisBuild = latestOutcomeFor(dataSource, manifest.schemaFingerprint());
        if (lastReachedByThisBuild.isEmpty()) {
            return Optional.empty();
        }
        Optional<HistoryPoint> latestOverall = latestOutcomeOverall(dataSource);
        HistoryPoint mine = lastReachedByThisBuild.get();
        HistoryPoint other = latestOverall.orElse(null);

        // QUAL-55 (permanent, never-throws diagnostic -- do not remove). The originally-observed
        // symptom was Trigger C silently not firing; this line is what finally named the mechanism,
        // printing TIE-DETECTED for two rows sharing a millisecond. It stays on after the fix: a tie
        // is now EXPECTED and HANDLED, and a tie line carrying seqDecision=SEQ in a green gate run is
        // the positive evidence that the seq column is doing real work. See ledger/items/QUAL-55.yml.
        String decisionSource = (mine.seq() != null && other != null && other.seq() != null)
                ? "SEQ" : "TIMESTAMP-FALLBACK";
        try {
            boolean tie = other != null && other.appliedAtUtc() == mine.appliedAtUtc();
            System.out.println("[QUAL-55-DIAG-TRIGGERC] thread=" + Thread.currentThread().getName()
                    + " at=" + Instant.now()
                    + " thisFp=" + manifest.schemaFingerprint()
                    + " thisBuildLastReachedMillis=" + mine.appliedAtUtc()
                    + " overallLatestMillis=" + (other == null ? "none" : Long.toString(other.appliedAtUtc()))
                    + " overallLatestFp=" + (other == null ? "none" : other.toFingerprint())
                    + " thisSeq=" + (mine.seq() == null ? "none" : mine.seq())
                    + " overallSeq=" + (other == null || other.seq() == null ? "none" : other.seq())
                    + " seqDecision=" + decisionSource
                    + (tie ? " TIE-DETECTED" : ""));
        } catch (RuntimeException ignored) {
            // diagnostic only -- must never affect the boot
        }

        if (other == null) {
            return Optional.empty();
        }
        // Hoisted ahead of the comparison (same condition as before, clearer position): if the newest
        // row IS this build's own, nothing moved past it. This is the intra-boot case -- an in-place
        // rename/relax/widen pass writes an APPLIED row for THIS fingerprint via recordStepPass
        // earlier in the same beforeMigrateDecision call, so the newest row is very often our own.
        if (manifest.schemaFingerprint().equals(other.toFingerprint())) {
            return Optional.empty();
        }
        // Strict > on the sequence when both rows have one -- the real ordering signal. A tie on
        // applied_at_utc is no longer ambiguous, and a tie-INCLUSIVE comparison is NOT an acceptable
        // substitute: `applied_at_utc >= ?` was implemented on 2026-09-08 and broke 5-6 of ~47
        // SchemaLifecycleExecutorProofMatrixTest scenarios, because it cannot tell an older row that
        // happens to tie from a genuinely later one. Falls back to the pre-QUAL-55 timestamp
        // comparison when either row predates the seq column (an upgraded database, or a row written
        // by an older jar) -- identical behaviour to before, for exactly the rows that had it.
        boolean movedPast = "SEQ".equals(decisionSource)
                ? other.seq() > mine.seq()
                : other.appliedAtUtc() > mine.appliedAtUtc();
        return movedPast ? Optional.of(other) : Optional.empty();
    }

    /** {@code APPLIED}/{@code MANUALLY_MARKED_DONE} are the outcomes that represent a REAL, recorded
     * advance of this database's schema state -- as opposed to {@code REFUSED}/{@code PARTIAL-CRASH}
     * (nothing durably changed) or the {@code EXTERNAL_*} outcomes (REG-7.1's read-only ownership
     * mode, which never writes {@code npdev_schema_metadata} and is not part of this fingerprint-
     * pointer lifecycle at all).
     *
     * <p>QUAL-55: returns the whole point, not just the timestamp, and sorts by {@code seq} as well.
     * Two rows in one millisecond used to make BOTH the selected row and the later comparison
     * arbitrary. Renamed from {@code latestOutcomeTimestamp} because it no longer returns one. */
    private static Optional<HistoryPoint> latestOutcomeFor(DataSource dataSource, String toFingerprint) {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT to_fingerprint, applied_at_utc, seq FROM " + HISTORY_TABLE
                            + " WHERE to_fingerprint = ? AND outcome IN ("
                            + "'APPLIED', 'MANUALLY_MARKED_DONE') ORDER BY applied_at_utc DESC, COALESCE(seq, 0) DESC")) {
                statement.setString(1, toFingerprint);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(readHistoryPoint(resultSet)) : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /**
     * STOR-28 (B4 lift): true when the most recent APPLIED/MANUALLY_MARKED_DONE row already targets
     * {@code fingerprint} -- i.e. a prior boot already converged the schema-diff/backfill/rename
     * machinery for this exact build, so {@link MigrationPreflight} can call this database's
     * NPDev-owned schema work done without taking the migration lock to find out. Reuses {@link
     * #latestOutcomeOverall} rather than a second query -- one read, one source of truth.
     */
    static boolean atOrPastFingerprint(DataSource dataSource, String fingerprint) {
        return latestOutcomeOverall(dataSource)
                .map(HistoryPoint::toFingerprint)
                .filter(fingerprint::equals)
                .isPresent();
    }

    private static Optional<HistoryPoint> latestOutcomeOverall(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT to_fingerprint, applied_at_utc, seq FROM " + HISTORY_TABLE + " WHERE outcome IN ("
                            + "'APPLIED', 'MANUALLY_MARKED_DONE') ORDER BY applied_at_utc DESC, COALESCE(seq, 0) DESC")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(readHistoryPoint(resultSet)) : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /** Reads the (to_fingerprint, applied_at_utc, seq) triple both queries above select, in that
     *  column order. {@code getLong} returns 0 for a SQL NULL, so {@code wasNull()} -- called
     *  IMMEDIATELY after the getLong for that column, which is the contract -- is the only way to
     *  tell a real seq of 0 from an absent one. */
    private static HistoryPoint readHistoryPoint(ResultSet resultSet) throws SQLException {
        String toFingerprint = resultSet.getString(1);
        long appliedAtUtc = resultSet.getLong(2);
        long rawSeq = resultSet.getLong(3);
        Long seq = resultSet.wasNull() ? null : rawSeq;
        return new HistoryPoint(toFingerprint, appliedAtUtc, seq);
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
                            + " ORDER BY applied_at_utc DESC, COALESCE(seq, 0) DESC")) {
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
     *
     * <p>QUAL-55: {@code seq} is a monotonic ordering signal, assigned application-side as
     * {@code MAX(seq)+1}. It exists because {@code applied_at_utc} is millisecond wall-clock and
     * {@code id} is a random UUID, so two rows written in the same millisecond had NO way to be
     * ordered -- which silently disabled Trigger C. Deliberately nullable: rows predating the column,
     * and rows written by an older jar (a real case here -- this subsystem exists for rollbacks),
     * both carry NULL and fall back to the timestamp comparison.
     *
     * <p>npdev-schema-history-seq (twin-pair token -- see scripts/quality/twin-pair-registry.json;
     * seven test files hand-roll their own CREATE TABLE for this table and must stay in step).
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
                        + "outcome " + InternalDdlTypes.text() + " NOT NULL, "
                        + "seq BIGINT)")
        )) {
            statement.executeUpdate();
        }
        // Upgrade path for a table that predates the seq column. Guarded by an explicit metadata
        // check even though guardedAddColumn already yields an idempotent statement: this method runs
        // on EVERY history write, so firing the ALTER unconditionally would issue that DDL forever
        // rather than once. Measured on the identical from_fingerprint precedent in MigrationMarkStore
        // -- it shifted DDL-call indices in SchemaLifecycleExecutorDestructiveCrashRecoveryTest's
        // fault-injection harness, which counts ALTER TABLE statements. A brand new install already
        // has the column from the CREATE above, so this branch never fires there.
        if (!hasSeqColumn(connection)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlDialects.active().guardedAddColumn(HISTORY_TABLE, "seq",
                            "ALTER TABLE " + HISTORY_TABLE + " ADD COLUMN seq BIGINT"))) {
                statement.executeUpdate();
            }
            backfillSeq(connection);
        }
    }

    /** Copied from {@link MigrationMarkStore}'s {@code hasFromFingerprintColumn}: probes both the
     *  lower- and upper-case spellings of the table name, because engines fold unquoted identifiers
     *  differently and {@code DatabaseMetaData.getColumns} matches literally. */
    private static boolean hasSeqColumn(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(HISTORY_TABLE.toLowerCase(Locale.ROOT), HISTORY_TABLE.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if ("seq".equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Assigns a sequence to rows written before the column existed, ordered by the best signal those
     * rows have ({@code applied_at_utc}, then {@code id} for a stable tie order). A Java-side loop
     * rather than a window function on purpose: {@code UPDATE ... FROM (SELECT ROW_NUMBER() ...)} has
     * four different spellings across this project's four engines, and this table holds one row per
     * migration pass -- it is tiny. Resumable: the base is the current MAX, so a partially completed
     * backfill continues correctly rather than colliding.
     */
    private static void backfillSeq(Connection connection) throws SQLException {
        long next;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) FROM " + HISTORY_TABLE);
                ResultSet resultSet = statement.executeQuery()) {
            next = resultSet.next() ? resultSet.getLong(1) + 1L : 1L;
        }
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + HISTORY_TABLE + " WHERE seq IS NULL ORDER BY applied_at_utc ASC, id ASC");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + HISTORY_TABLE + " SET seq = ? WHERE id = ?")) {
            for (String id : ids) {
                statement.setLong(1, next++);
                statement.setString(2, id);
                statement.addBatch();
            }
            statement.executeBatch();
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
                            + "classification, items_json, ack_token_used, outcome, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
                statement.setLong(9, nextSeq(connection));
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

    /**
     * The next monotonic sequence value. Read on the SAME connection as the insert that consumes it,
     * inside the migration lock, which is what makes read-then-write safe here.
     *
     * <p><b>Deliberately a separate statement, not a subquery in the INSERT's VALUES clause.</b>
     * MySQL rejects a subquery in VALUES that references the target table (error 1093, "You can't
     * specify target table for update in FROM clause"). Two statements are portable on all four
     * engines.
     *
     * <p><b>No UNIQUE index on seq, on purpose.</b> Two concurrent writers outside the lock could
     * produce a duplicate; a duplicate makes the strict {@code >} comparison false, so Trigger C
     * stays silent -- exactly the pre-fix behaviour, never a false refusal. A unique constraint would
     * turn that benign degradation into a failed insert during a migration, i.e. a boot failure.
     */
    private static long nextSeq(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM " + HISTORY_TABLE);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 1L;
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
                            + "classification, items_json, ack_token_used, outcome, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, fromFingerprint);
                statement.setString(4, toFingerprint);
                statement.setString(5, classificationText);
                statement.setString(6, itemsJson(itemDetails));
                statement.setNull(7, Types.VARCHAR);
                statement.setString(8, outcome);
                statement.setLong(9, nextSeq(connection));
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
