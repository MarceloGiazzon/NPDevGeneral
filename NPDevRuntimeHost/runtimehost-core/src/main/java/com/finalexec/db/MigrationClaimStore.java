package com.finalexec.db;

import java.net.InetAddress;
import java.net.UnknownHostException;
import com.npdev.kernel.storage.sql.SqlDialects;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * The migration slot: {@link MigrationMutex} is the LOCK, and this class is the human-readable
 * record of who holds it.
 *
 * <h2>R9.3 changed which of those two the boot depends on</h2>
 *
 * <p>Until R9.3 this class WAS the lock, and it locked by refusing. Postgres probed
 * {@code pg_try_advisory_lock} (a non-blocking try) and threw when it came back false; every other
 * engine locked the canonical row, read the holder, and threw when it was not blank. Two instances
 * started at the same moment therefore produced one migrated database and one dead process -- and on
 * a genuinely fresh database every engine except Postgres skipped the mechanism ENTIRELY, so the
 * first-ever boot had no protection of any kind. Both are now {@link MigrationMutex}'s job: it
 * blocks until the slot is free (so simultaneous boots serialize instead of one losing) and it needs
 * no table on three of four engines (so the virgin-database window is covered too).
 *
 * <p>What remains here is bookkeeping, and it is deliberately NOT load-bearing for exclusion:
 * {@link #current} feeds the ControlPanel's "who holds the migration" display and
 * {@link MigrationMutex}'s timeout diagnostic, and {@link #clear} is the operator escape hatch.
 * <b>A stale holder row no longer blocks anything.</b> The mutex is scoped to the connection that
 * took it, so an instance that crashed mid-migration has already released it -- the next boot walks
 * straight in and overwrites the row. That is the point: a lock leaked by a crashed boot must not
 * deadlock every future boot, and before R9.3 it did, until an operator ran {@link #clear} by hand.
 *
 * <p>The REG-91 diagnostics below are unchanged and still throw, because they describe a claim TABLE
 * that is genuinely broken (a legacy {@code NOT NULL} shape, a seed insert rejected for a reason
 * that is not a duplicate, a canonical row that is absent) rather than a slot that is busy.
 */
public final class MigrationClaimStore {

    static final String TABLE = "npdev_schema_migration_claim";
    private static final String CLAIM_KEY = "schema-migration";

    /**
     * REG-91. The "nobody holds it" holder, written as non-null sentinels rather than {@code NULL}.
     *
     * <p>This is not cosmetic. Until Move 9 A1 this class's {@code CREATE TABLE IF NOT EXISTS}
     * declared {@code instance_id TEXT NOT NULL, claimed_at_utc BIGINT NOT NULL} -- correct then,
     * because the row was inserted per claim and deleted on release, so an unheld slot was an ABSENT
     * row. A1 changed the row to persist with its holder blanked, and relaxed the DDL to match. But
     * {@code CREATE TABLE IF NOT EXISTS} is a no-op against a table that already exists, so every
     * database that ever booted a pre-A1 build keeps the strict shape forever, and an all-{@code NULL}
     * unheld row is rejected by it -- permanently, on every boot, with no self-healing path.
     *
     * <p>Sentinels are what makes both shapes work from one code path: blank/zero satisfies the legacy
     * {@code NOT NULL} columns and still reads as unheld to {@link #current} and to
     * {@link #recordHolder}'s own {@code instanceId == null || instanceId.isBlank()} test, which
     * already tolerated either. The
     * shape is deliberately NOT rewritten (no {@code ALTER ... DROP NOT NULL} sweep): tolerating the
     * old shape needs no DDL against a database whose migration lock is, by definition, not yet held.
     */
    private static final String UNHELD_INSTANCE_ID = "";
    private static final String UNHELD_HOSTNAME = "";
    private static final long UNHELD_CLAIMED_AT_UTC = 0L;

    /**
     * What each live claim is holding, keyed by instanceId (unique per boot within this JVM), so
     * {@link #release} can give back exactly what this boot took. A crashed JVM simply loses this
     * in-memory entry, which is fine and is the whole design: the lock is scoped to a database
     * connection, so the engine released it when that connection died with the process.
     */
    private static final ConcurrentHashMap<String, HeldSlot> HELD_SLOTS = new ConcurrentHashMap<>();

    /**
     * {@code rowRecorded} is not bookkeeping about bookkeeping -- it is a REG-7.2 guard.
     *
     * <p>A fresh-database claim deliberately writes no holder row, so the claim table may not exist
     * when this boot releases. {@link #release} used to issue its blanking {@code UPDATE}
     * unconditionally and swallow the resulting failure as a log line (harmless-looking, and already
     * happening on every fresh Postgres boot before R9.3). Making that path robust by creating the
     * table first would be worse than the noise: a fresh boot that fails BEFORE
     * {@code flyway.migrate()} runs still reaches this release, and creating a table in Flyway's
     * schema on a still-virgin database is exactly REG-7.2 -- a first boot that fails for one reason
     * would leave the database unable to boot for a different one. So the release simply skips the
     * row it knows it never wrote.
     */
    private record HeldSlot(MigrationMutex.Held mutex, boolean rowRecorded) {
    }

    private MigrationClaimStore() {
    }

    public record Claim(String claimKey, String instanceId, String hostname, long claimedAtUtc) {
    }

    static void ensureTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateTable(TABLE,
                        "CREATE TABLE " + TABLE
                        + " (claim_key " + InternalDdlTypes.keyText() + " PRIMARY KEY, "
                        + "instance_id " + InternalDdlTypes.text() + ", "
                        + "hostname " + InternalDdlTypes.text() + ", claimed_at_utc BIGINT)")
        )) {
            statement.executeUpdate();
        }
    }

    /**
     * Idempotently seeds the one canonical row this table ever holds, with a blank (unclaimed)
     * holder. Safe under a concurrent race: if two instances both attempt this insert, the PK
     * constraint lets exactly one succeed; the loser's UNIQUE violation is swallowed here since
     * either way the row now exists for {@link #recordHolder} to read back and update.
     *
     * <p>REG-91: the swallow is limited to a UNIQUE violation, as {@link #isUniqueViolation} defines
     * it for the ACTIVE ENGINE (STOR-12 -- it was Postgres's 23505 for everyone). Anything else is
     * rethrown with the statement and the driver's own message attached, because every other insert
     * failure leaves the table without the row this method exists to guarantee -- and silently
     * "succeeding" there is what turned one bad {@code NOT NULL} column into an unbootable app
     * reporting {@code No data is available} from a completely different line.
     */
    private static void ensureCanonicalRow(Connection connection) throws SQLException {
        String sql = "INSERT INTO " + TABLE
                + " (claim_key, instance_id, hostname, claimed_at_utc) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, CLAIM_KEY);
            statement.setString(2, UNHELD_INSTANCE_ID);
            statement.setString(3, UNHELD_HOSTNAME);
            statement.setLong(4, UNHELD_CLAIMED_AT_UTC);
            statement.executeUpdate();
        } catch (SQLException failure) {
            if (isUniqueViolation(failure)) {
                // Expected under a concurrent bootstrap race, or on every non-first boot -- the row
                // already exists, which is exactly what this method exists to ensure.
                return;
            }
            throw new SQLException(
                    "NPDev schema lifecycle: could not seed the canonical migration-claim row in " + TABLE
                            + ". This is NOT a duplicate-row race (SQLState " + failure.getSQLState()
                            + ", error code " + failure.getErrorCode() + "), so the row is genuinely absent and the "
                            + "boot cannot proceed. Statement: " + sql + " with values ('" + CLAIM_KEY + "', '"
                            + UNHELD_INSTANCE_ID + "', '" + UNHELD_HOSTNAME + "', " + UNHELD_CLAIMED_AT_UTC
                            + "). Driver said: " + failure.getMessage(),
                    failure.getSQLState(), failure.getErrorCode(), failure);
        }
    }

    /**
     * True only for a unique/primary-key violation. Checked on {@code SQLState} and the vendor error
     * code, never on the message text (REG-91) -- message text is localised and driver-specific,
     * and getting this predicate wrong in the permissive direction is the whole bug.
     */
    /**
     * Delegates to the active dialect -- {@code 23505} is Postgres and H2's spelling, not everyone's.
     *
     * <p>MySQL and SQL Server both report SQLSTATE {@code 23000} for the WHOLE integrity class and
     * distinguish a duplicate only by their own error number (1062 / 2627). So this test used to say
     * "not a duplicate" for the ordinary case of the canonical row already existing, and the app
     * refused to boot on both engines (STOR-12) -- with a message asserting the opposite of the
     * truth: <i>"This is NOT a duplicate-row race, so the row is genuinely absent"</i>.
     *
     * <p>The narrowness the old constant's javadoc argued for is preserved and is now per-engine:
     * see {@link SqlDialect#isUniqueViolation}, which names only the codes that mean UNIQUE. Widening
     * to the whole {@code 23} class would swallow a NOT NULL or foreign-key failure, which really
     * does leave the table without the row -- the state REG-91 wedged on.
     */
    private static boolean isUniqueViolation(SQLException failure) {
        return SqlDialects.active().isUniqueViolation(failure);
    }

    /**
     * Takes the single logical migration slot for THIS boot, WAITING for any migration already in
     * flight rather than refusing (R9.3). Returns only once this boot holds it; see
     * {@link MigrationMutex} for the wait budget and the two mechanisms.
     *
     * @param freshDatabase true when no schema fingerprint is stored yet -- a genuinely virgin
     *                      database. The MUTEX is taken either way; this flag now governs only
     *                      whether the human-readable claim row is written, because writing it means
     *                      creating a table in Flyway's schema, and doing that ahead of
     *                      {@code flyway.migrate()} on a virgin schema is REG-7.2. So a first-ever
     *                      boot is locked but not recorded -- there is no earlier instance for an
     *                      operator to read about anyway.
     */
    static Claim claim(DataSource dataSource, boolean freshDatabase) {
        String instanceId = UUID.randomUUID().toString();
        String hostname = localHostname();
        long claimedAtUtc = System.currentTimeMillis();
        MigrationMutex.Held mutex = MigrationMutex.acquire(dataSource);
        try {
            if (!freshDatabase) {
                recordHolder(dataSource, instanceId, hostname, claimedAtUtc);
            }
            HELD_SLOTS.put(instanceId, new HeldSlot(mutex, !freshDatabase));
            return new Claim(CLAIM_KEY, instanceId, hostname, claimedAtUtc);
        } catch (RuntimeException failure) {
            // Never leave the mutex held by a boot that is not going to migrate -- it is
            // connection-scoped, so a leak here would hold the slot until this JVM exits.
            MigrationMutex.release(mutex);
            throw failure;
        }
    }

    /**
     * Writes the "this instance holds it" row. Not part of exclusion -- {@link MigrationMutex}
     * already granted the slot before this runs -- but still STRICT rather than best-effort, because
     * every failure it can raise (REG-91) means the claim table itself is malformed, and a boot that
     * silently cannot record its own migration is how REG-91 presented in the first place.
     *
     * <p>Any holder already in the row belongs to an instance that crashed: a LIVE one would still
     * hold the mutex and this boot would be waiting, not here. So it is overwritten, not treated as
     * a collision.
     */
    private static void recordHolder(DataSource dataSource, String instanceId, String hostname, long claimedAtUtc) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                ensureCanonicalRow(connection);
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT instance_id FROM " + TABLE + " WHERE claim_key = ?")) {
                    select.setString(1, CLAIM_KEY);
                    try (ResultSet resultSet = select.executeQuery()) {
                        // REG-91: next()'s return value is the difference between "the row is
                        // unheld" and "the row this mechanism depends on does not exist". Ignoring
                        // it reported the latter as the driver's generic "No data is available",
                        // from a line that names neither the table nor the row.
                        if (!resultSet.next()) {
                            throw missingCanonicalRowException();
                        }
                        String heldBy = resultSet.getString(1);
                        if (heldBy != null && !heldBy.isBlank()) {
                            System.out.println("NPDev schema lifecycle: taking over the migration claim row from "
                                    + "instance " + heldBy + ", which never released it -- that instance is gone "
                                    + "(the migration lock is connection-scoped, so a live one would still hold it "
                                    + "and this boot would be waiting). No operator action is needed.");
                        }
                    }
                }
                upsertHolder(connection, instanceId, hostname, claimedAtUtc);
                connection.commit();
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                if (failure instanceof PreformattedFailure signal) {
                    throw signal.asIllegalStateException();
                }
                throw new IllegalStateException("Failed to record the migration claim", failure);
            }
        } catch (PreformattedFailure signal) {
            throw signal.asIllegalStateException();
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to record the migration claim", failure);
        }
    }

    private static void upsertHolder(Connection connection, String instanceId, String hostname, long claimedAtUtc)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE + " SET instance_id = ?, hostname = ?, claimed_at_utc = ? WHERE claim_key = ?"
        )) {
            statement.setString(1, instanceId);
            statement.setString(2, hostname);
            statement.setLong(3, claimedAtUtc);
            statement.setString(4, CLAIM_KEY);
            statement.executeUpdate();
        }
    }

    /**
     * Carries an already-diagnosed, human-readable failure ("someone else holds it"; REG-91's "the
     * canonical row is missing") through methods that must throw {@link SQLException}, so the caller
     * rethrows the explanation rather than burying it as a cause under a generic wrapper.
     */
    private static final class PreformattedFailure extends SQLException {
        private final IllegalStateException delegate;

        PreformattedFailure(IllegalStateException delegate) {
            this.delegate = delegate;
        }

        IllegalStateException asIllegalStateException() {
            return delegate;
        }
    }

    /**
     * REG-91: the canonical row is absent although {@link #ensureCanonicalRow} reported success --
     * reachable when the seed insert failed with a genuine UNIQUE violation raised by some OTHER
     * constraint on the table (so it was legitimately swallowed as a race) while this key's row
     * still does not exist. Names the table and the key, which the driver's own
     * {@code No data is available} did not.
     */
    private static PreformattedFailure missingCanonicalRowException() {
        return new PreformattedFailure(new IllegalStateException(
                "The canonical migration claim row is missing: table " + TABLE + " has no row with claim_key = '"
                        + CLAIM_KEY + "', so the migration lock cannot be taken. NPDev seeds this row itself on "
                        + "every boot, so its absence means the seed insert was rejected -- inspect that table's "
                        + "constraints. Recover by inserting the unheld row by hand: INSERT INTO " + TABLE
                        + " (claim_key, instance_id, hostname, claimed_at_utc) VALUES ('" + CLAIM_KEY + "', '"
                        + UNHELD_INSTANCE_ID + "', '" + UNHELD_HOSTNAME + "', " + UNHELD_CLAIMED_AT_UTC + "). "
                        + "POST /api/admin/schema-migration/clear-claim does NOT help here -- there is no holder "
                        + "to clear, the row itself is absent. See docs/SCHEMA_EVOLUTION.md#collision-detection."));
    }

    /**
     * Releases the slot THIS boot holds: the mutex first, then the human-readable row -- but only
     * when this boot actually wrote one (see {@link HeldSlot}). A failure here is logged but never
     * propagated: the migration this protected has already finished by the time this runs.
     */
    static void release(DataSource dataSource, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        HeldSlot slot = HELD_SLOTS.remove(instanceId);
        MigrationMutex.release(slot == null ? null : slot.mutex());
        if (slot != null && !slot.rowRecorded()) {
            // Nothing to blank, and touching the claim table here could create it on a database
            // Flyway has not migrated yet -- see HeldSlot.
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            // REG-91: sentinels, not NULL -- a pre-A1 database's NOT NULL columns reject the NULL
            // form, and this catch would swallow that into a log line, leaving the claim held
            // forever and every subsequent boot refused as a collision.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET instance_id = ?, hostname = ?, claimed_at_utc = ? "
                            + "WHERE claim_key = ? AND instance_id = ?")) {
                statement.setString(1, UNHELD_INSTANCE_ID);
                statement.setString(2, UNHELD_HOSTNAME);
                statement.setLong(3, UNHELD_CLAIMED_AT_UTC);
                statement.setString(4, CLAIM_KEY);
                statement.setString(5, instanceId);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            System.out.println("NPDev schema lifecycle: failed releasing migration claim row for instance " + instanceId
                    + " (the migration it protected already finished -- only this cleanup write failed): "
                    + exception.getMessage());
        }
    }

    /** The current claim, if any. Never throws -- an unreachable/missing table, or an unclaimed
     * (blank-holder) row, both mean "no claim". */
    public static Optional<Claim> current(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT claim_key, instance_id, hostname, claimed_at_utc FROM " + TABLE + " WHERE claim_key = ?")) {
                statement.setString(1, CLAIM_KEY);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    String instanceId = resultSet.getString(2);
                    if (instanceId == null || instanceId.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Claim(
                            resultSet.getString(1), instanceId, resultSet.getString(3), resultSet.getLong(4)));
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /**
     * REG-7.3's manual escape hatch (D3): unconditionally clears the row's holder back to blank,
     * regardless of who holds it. Still SUPERUSER-gated and still exposed by the ControlPanel.
     *
     * <p><b>R9.3 demoted this from a recovery step to a cosmetic one.</b> It clears the display row
     * and cannot touch a live mutex, which is released only by its holder calling
     * {@link #release} or by that holder's database connection dying. Before R9.3 a crashed
     * instance's row DID block every later boot and this was the only way out; now a crashed
     * instance releases the mutex by disappearing, and the next boot overwrites the row itself. So
     * running this fixes a misleading "who holds it" display and nothing else -- if boots are
     * actually queueing, the holder is alive and clearing its row will not free them.
     */
    public static void clear(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            // REG-91: sentinels, not NULL -- see release(). Here the failure is not even swallowed:
            // against a pre-A1 database the operator's documented escape hatch itself threw.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET instance_id = ?, hostname = ?, claimed_at_utc = ? WHERE claim_key = ?")) {
                statement.setString(1, UNHELD_INSTANCE_ID);
                statement.setString(2, UNHELD_HOSTNAME);
                statement.setLong(3, UNHELD_CLAIMED_AT_UTC);
                statement.setString(4, CLAIM_KEY);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed clearing the migration claim", exception);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort cleanup only
        }
    }

    private static String localHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException exception) {
            return "unknown-host";
        }
    }
}
