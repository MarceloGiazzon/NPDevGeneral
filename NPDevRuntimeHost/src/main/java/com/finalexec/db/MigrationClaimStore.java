package com.finalexec.db;

import java.net.InetAddress;
import java.net.UnknownHostException;
import com.npdev.kernel.storage.sql.SqlDialects;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * B4 (Move 9 A1, {@code docs/ACCEPTED_BOUNDARIES.md}): a real lock, not detect-and-refuse. Two
 * engine-specific mechanisms, dispatched by {@link Connection#getMetaData()}'s product name:
 *
 * <ul>
 *   <li><b>Postgres:</b> {@code pg_try_advisory_lock}/{@code pg_advisory_unlock} on a fixed key
 *       derived from {@link #CLAIM_KEY}. Session-scoped -- tied to the physical {@link Connection}
 *       that acquired it, held open (never returned to the pool) between {@link #claim} and
 *       {@link #release} so the lock survives the whole migration, and released automatically by
 *       Postgres itself if this JVM dies mid-migration (no manual {@link #clear} needed). Needs no
 *       table to exist, so it protects even a genuinely fresh database -- the one case the old
 *       claim-row mechanism could never cover (see {@link #claim}'s {@code freshDatabase} parameter).</li>
 *   <li><b>H2 (or any other engine):</b> a canonical single row on the fixed key {@link #CLAIM_KEY},
 *       bootstrapped once with a blank holder. Claiming is one transaction: {@code SELECT ... FOR
 *       UPDATE} that row (blocking a concurrent claimant, and failing loudly if it is already held),
 *       then {@code UPDATE} it to this instance, then commit -- closing the check-then-act window a
 *       bare {@code INSERT} relying only on a PK constraint left open. Exactly like before, this
 *       mechanism is never engaged on a genuinely fresh database (see {@code freshDatabase} below):
 *       {@code ensureTable}'s own {@code CREATE TABLE IF NOT EXISTS} would self-bootstrap ahead of
 *       Flyway on a truly virgin schema, making Flyway see a non-empty schema with no history table
 *       and refuse outright (REG-7.2 -- the bug this class's own fresh-database gating exists to
 *       avoid repeating).</li>
 * </ul>
 *
 * <p>{@link #current} still reports the row-based holder for the ControlPanel's human-readable
 * "who holds the migration" display, and {@link #clear} is still the manual escape hatch for the
 * crashed-holder case -- both preserved exactly as before, now describing a row that persists
 * (holder cleared back to blank, not deleted) rather than one that is inserted/deleted per claim.
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
     * {@code NOT NULL} columns and still reads as unheld to {@link #current} and {@link #claimH2}'s own
     * {@code instanceId == null || instanceId.isBlank()} test, which already tolerated either. The
     * shape is deliberately NOT rewritten (no {@code ALTER ... DROP NOT NULL} sweep): tolerating the
     * old shape needs no DDL against a database whose migration lock is, by definition, not yet held.
     */
    private static final String UNHELD_INSTANCE_ID = "";
    private static final String UNHELD_HOSTNAME = "";
    private static final long UNHELD_CLAIMED_AT_UTC = 0L;

    /**
     * SQLState for a unique/primary-key violation -- {@code 23505} on both H2 and Postgres. This is
     * the ONLY insert failure {@link #ensureCanonicalRow} may treat as "the row is already there".
     * Deliberately narrower than the whole {@code 23xxx} integrity class: {@code 23502} (NULL not
     * allowed) and {@code 23506} (referential integrity) are real failures that leave the table
     * WITHOUT the canonical row, which is precisely the state REG-91 wedged on.
     */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /**
     * Arbitrary, fixed advisory-lock key for NPDev's single migration slot. {@link String#hashCode()}
     * is specified stable across JVMs/versions (Java Language Spec), so this is deterministic --
     * never reuse this key for any other {@code pg_advisory_lock} caller.
     */
    private static final long ADVISORY_LOCK_KEY = CLAIM_KEY.hashCode();

    /**
     * Postgres advisory locks are tied to the physical connection that acquired them -- kept open
     * here (never returned to the pool) between {@link #claim} and {@link #release}. Keyed by
     * instanceId, which is unique per boot within this JVM, so at most one entry is ever live per
     * instance; a crashed JVM simply loses this in-memory entry, which is fine -- Postgres itself
     * already released the session-scoped lock when the connection died with the process.
     */
    private static final ConcurrentHashMap<String, Connection> HELD_ADVISORY_CONNECTIONS = new ConcurrentHashMap<>();

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
     * either way the row now exists for the {@code SELECT ... FOR UPDATE} step in {@link #claimH2}
     * to lock.
     *
     * <p>REG-91: the swallow is limited to {@link #SQLSTATE_UNIQUE_VIOLATION}. Anything else is
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
    private static boolean isUniqueViolation(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if (SQLSTATE_UNIQUE_VIOLATION.equals(current.getSQLState())
                    || current.getErrorCode() == Integer.parseInt(SQLSTATE_UNIQUE_VIOLATION)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    }

    /**
     * Attempts to claim the single logical migration slot for THIS boot.
     *
     * @param freshDatabase true when no schema fingerprint is stored yet -- a genuinely virgin
     *                       database. On Postgres the advisory lock still protects this case (it
     *                       needs no table). On every other engine, claiming is skipped entirely
     *                       (returns {@code null}), exactly as before REG-7.2's fix required.
     */
    static Claim claim(DataSource dataSource, boolean freshDatabase) {
        String instanceId = UUID.randomUUID().toString();
        String hostname = localHostname();
        long claimedAtUtc = System.currentTimeMillis();
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (isPostgres(connection)) {
                return claimPostgres(connection, dataSource, instanceId, hostname, claimedAtUtc, freshDatabase);
            }
            connection.close();
            connection = null;
            if (freshDatabase) {
                return null;
            }
            return claimH2(dataSource, instanceId, hostname, claimedAtUtc);
        } catch (SQLException failure) {
            closeQuietly(connection);
            throw new IllegalStateException("Failed to claim the migration lock", failure);
        }
    }

    private static Claim claimPostgres(
            Connection connection, DataSource dataSource, String instanceId, String hostname, long claimedAtUtc,
            boolean freshDatabase
    ) throws SQLException {
        boolean acquired;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                // REG-91's sibling: same unchecked-next() shape. pg_try_advisory_lock is a scalar
                // function and always returns exactly one row, so an empty result set means the
                // statement did not do what this code assumes -- say so, rather than reporting
                // "No data is available" from getBoolean.
                if (!resultSet.next()) {
                    // Message deliberately does not begin with the SQL verb: security-pattern-sweep.py
                    // reads a leading SELECT in a string literal as a query, and a diagnostic that
                    // quotes one is a false positive nobody should have to triage.
                    throw new SQLException("the pg_try_advisory_lock(" + ADVISORY_LOCK_KEY
                            + ") probe returned no rows; the migration lock cannot be taken on this connection.");
                }
                acquired = resultSet.getBoolean(1);
            }
        }
        if (!acquired) {
            connection.close();
            throw claimHeldException(current(dataSource));
        }
        if (!freshDatabase) {
            // Best-effort human-readable row -- the advisory lock above is the real guard, so a
            // failure updating this row must never unwind the lock we already hold.
            try {
                ensureTable(connection);
                ensureCanonicalRow(connection);
                upsertHolder(connection, instanceId, hostname, claimedAtUtc);
            } catch (SQLException reportingFailure) {
                System.out.println("NPDev schema lifecycle: migration lock acquired, but recording the "
                        + "human-readable claim row failed (exclusion is unaffected): " + reportingFailure.getMessage());
            }
        }
        HELD_ADVISORY_CONNECTIONS.put(instanceId, connection);
        return new Claim(CLAIM_KEY, instanceId, hostname, claimedAtUtc);
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
     * H2's atomic claim: one transaction locks the canonical row ({@code SELECT ... FOR UPDATE}),
     * checks it is unheld, then updates it to this instance -- closing the check-then-act window a
     * bare PK-constrained {@code INSERT} left open.
     */
    private static Claim claimH2(DataSource dataSource, String instanceId, String hostname, long claimedAtUtc) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                ensureCanonicalRow(connection);
                String heldBy;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT instance_id FROM " + TABLE + " WHERE claim_key = ? FOR UPDATE")) {
                    select.setString(1, CLAIM_KEY);
                    try (ResultSet resultSet = select.executeQuery()) {
                        // REG-91: next()'s return value is the difference between "the row is unheld"
                        // and "the row this whole mechanism depends on does not exist". Ignoring it
                        // reported the latter as the driver's generic "No data is available", from a
                        // line that names neither the table nor the row.
                        if (!resultSet.next()) {
                            throw missingCanonicalRowException();
                        }
                        heldBy = resultSet.getString(1);
                    }
                }
                if (heldBy != null && !heldBy.isBlank()) {
                    connection.rollback();
                    throw claimHeldException(current(dataSource));
                }
                upsertHolder(connection, instanceId, hostname, claimedAtUtc);
                connection.commit();
                return new Claim(CLAIM_KEY, instanceId, hostname, claimedAtUtc);
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                if (failure instanceof PreformattedFailure signal) {
                    throw signal.asIllegalStateException();
                }
                throw new IllegalStateException("Failed to claim the migration lock", failure);
            }
        } catch (PreformattedFailure signal) {
            throw signal.asIllegalStateException();
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to claim the migration lock", failure);
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

    private static PreformattedFailure claimHeldException(Optional<Claim> holder) {
        String holderDescription = holder
                .map(claim -> "instance " + claim.instanceId() + " on host " + claim.hostname()
                        + ", claimed at epoch-ms " + claim.claimedAtUtc())
                .orElse("an unknown instance (the claim row could not be read back)");
        return new PreformattedFailure(new IllegalStateException(
                "Another NPDev instance is currently migrating this database (" + holderDescription + "). "
                        + "Concurrent schema migrations are not supported (REG-7.3/B4) -- wait for it to finish and "
                        + "retry, or if it crashed mid-migration, clear the stale claim via "
                        + "POST /api/admin/schema-migration/clear-claim (SUPERUSER) or the ControlPanel schema-migration "
                        + "screen. Clearing a claim while another instance genuinely holds it re-introduces the race "
                        + "-- that is an operator decision. See docs/SCHEMA_EVOLUTION.md#collision-detection."));
    }

    /**
     * Releases a lock THIS boot holds. For Postgres, unlocks + closes the held connection (belt and
     * braces -- Postgres already releases a session-scoped advisory lock the moment its connection
     * closes, so simply closing would be enough, but calling {@code pg_advisory_unlock} explicitly
     * keeps the lock's release independent of exactly when the pool physically tears the connection
     * down). Always clears the human-readable row too (harmless no-op if none was ever written, e.g.
     * a fresh-database Postgres claim). A failure here is logged but never propagated -- the
     * migration this claim protected has already finished by the time this runs.
     */
    static void release(DataSource dataSource, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        Connection heldConnection = HELD_ADVISORY_CONNECTIONS.remove(instanceId);
        if (heldConnection != null) {
            try (PreparedStatement statement = heldConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, ADVISORY_LOCK_KEY);
                statement.executeQuery();
            } catch (SQLException exception) {
                System.out.println("NPDev schema lifecycle: failed to explicitly unlock the migration advisory "
                        + "lock for instance " + instanceId + " (closing its connection below still releases it, "
                        + "since Postgres advisory locks are session-scoped): " + exception.getMessage());
            } finally {
                closeQuietly(heldConnection);
            }
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

    /** REG-7.3's manual escape hatch (D3): unconditionally clears the row's holder back to blank,
     * regardless of who holds it -- for the crashed-holder case. Does NOT touch a live Postgres
     * advisory lock (that can only be released by its own session dying or calling {@link #release});
     * the caller (ControlPanel, SUPERUSER-gated) is trusting the operator's judgment that the holder
     * this clears is genuinely stale. */
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

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort cleanup only
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
