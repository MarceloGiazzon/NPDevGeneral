package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.kernel.storage.sql.StorageCapability;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * R9.3: the mutex that makes two simultaneous boots SERIALIZE instead of one of them losing.
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code MigrationClaimStore} already excluded concurrent migrations, but it excluded them by
 * <b>refusing</b>: {@code pg_try_advisory_lock} is a non-blocking probe, and the row path read the
 * holder and threw. Two instances started by a supervisor at the same moment therefore produced one
 * migrated database and one dead process -- correct for data, useless for availability, and exactly
 * the shape that stops working when service supervision and restart automation make simultaneous
 * boots routine. This class keeps the mutual exclusion and adds the WAITING, so the second boot
 * proceeds normally once the first is done.
 *
 * <p>It also closes the window that had no lock at all. {@code claim(dataSource, freshDatabase)}
 * skipped locking entirely on a genuinely virgin database for every engine except Postgres, because
 * self-bootstrapping the claim table ahead of {@code flyway.migrate()} makes Flyway refuse the boot
 * with <i>"Found non-empty schema(s) 'public' but no schema history table"</i> (REG-7.2, verified
 * live on {@code simple-user-registry-h2local}). That is a real constraint, so this class is built
 * around it rather than through it -- see the two mechanisms below.
 *
 * <h2>Two mechanisms, chosen by CAPABILITY and never by engine name</h2>
 *
 * <ul>
 *   <li><b>{@link StorageCapability#SESSION_ADVISORY_LOCK} (Postgres, MySQL, SQL Server):</b> the
 *       engine's own named session lock. Needs no table, so the virgin-database window needs no
 *       special case at all.</li>
 *   <li><b>Everything else (H2 today):</b> a row lock on a single seeded row, held open in its own
 *       transaction. The table lives in a DEDICATED schema ({@link #FALLBACK_SCHEMA}) that Flyway
 *       does not manage, which is what keeps REG-7.2 shut: Flyway's schema stays genuinely empty on
 *       a first-ever boot while the lock still exists to be taken.</li>
 * </ul>
 *
 * <p>The waiting is done HERE, in one bounded retry loop, over a probe that never blocks -- rather
 * than by asking each engine to wait server-side. That is not a stylistic choice: Postgres cannot
 * wait with a timeout at all ({@code lock_timeout} does not apply to advisory locks, and
 * {@code pg_advisory_lock} waits forever), so a server-side wait would hang one engine's boot
 * indefinitely while the other three timed out. One loop means the deadline is one number with the
 * same meaning everywhere. The row-lock probe is {@code SELECT ... FOR UPDATE SKIP LOCKED}, which
 * returns zero rows instead of throwing when the row is held -- so neither path needs a lock-timeout
 * setting or an engine-specific error code to tell "contended" from "broken".
 *
 * <h2>Release: connection-scoped, deliberately not a lease</h2>
 *
 * <p>Both mechanisms are scoped to the CONNECTION that took them, and that connection is held open
 * (never returned to the pool) for the whole migration. A boot that crashes mid-migration therefore
 * releases the mutex the moment its socket dies -- the engine does it, with no timer, no heartbeat
 * and no operator step. A lease with an expiry was the alternative and is worse here: a migration
 * has no safe maximum duration (it is bounded by the size of the user's data, not by anything NPDev
 * knows), so any expiry short enough to recover a crash quickly is also short enough to hand a
 * second instance the lock while the first is still writing DDL -- which is the interleaving this
 * exists to prevent. The tradeoff is that a HUNG (not crashed) boot holds the mutex until it is
 * killed, and the diagnostic below says so rather than pretending otherwise.
 */
final class MigrationMutex {

    /**
     * The logical lock name -- the same constant {@code MigrationClaimStore} hashed when it spelled
     * {@code pg_try_advisory_lock} inline. The NUMBER Postgres ends up keying on does change, since
     * {@code PostgresDialect.advisoryLockKey} now derives it with FNV-1a rather than
     * {@code String.hashCode}; see that method for why the wider hash won.
     */
    static final String LOCK_NAME = "schema-migration";

    /**
     * The schema holding the row-lock fallback's table -- dedicated, and NOT the app's schema.
     *
     * <p>This single decision is what lets a first-ever boot be locked on an engine with no advisory
     * lock. Flyway checks the schemas it MANAGES for emptiness; a table in a schema it was never
     * pointed at leaves that check untouched, so REG-7.2 stays fixed while the fresh-boot window
     * stops being unprotected.
     */
    static final String FALLBACK_SCHEMA = "npdev_lock";

    static final String FALLBACK_TABLE = FALLBACK_SCHEMA + ".npdev_migration_mutex";

    /**
     * How long a boot waits for a migration already in flight before giving up. Overridable with
     * {@code -Dnpdev.schema.lock.waitSeconds} -- an operator whose migrations legitimately run
     * longer than this must be able to raise it without a rebuild, and the tests need to lower it to
     * assert the refusal path in seconds rather than minutes.
     */
    private static final String WAIT_SECONDS_PROPERTY = "npdev.schema.lock.waitSeconds";
    private static final long DEFAULT_WAIT_SECONDS = 300L;

    /** Probe interval. A boot-time operation taken once, so a coarse poll costs nothing. */
    private static final long POLL_MILLIS = 100L;

    private MigrationMutex() {
    }

    /**
     * The live mutex. Holds the connection it was taken on -- closing that connection is what
     * releases the lock on every engine, which is why this is not merely a boolean -- together with
     * the dialect that connection actually resolved to, so the release speaks the same engine the
     * acquire did without asking the driver a second time.
     */
    record Held(Connection connection, SqlDialect dialect, boolean advisory) {
    }

    static long waitMillis() {
        String configured = System.getProperty(WAIT_SECONDS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_WAIT_SECONDS * 1000L;
        }
        try {
            return Math.max(0L, Long.parseLong(configured.trim())) * 1000L;
        } catch (NumberFormatException ignored) {
            // A typo in an operator-set property must not change how long the mutex waits silently.
            System.out.println("NPDev schema lifecycle: ignoring unparseable " + WAIT_SECONDS_PROPERTY
                    + "='" + configured + "'; using the default " + DEFAULT_WAIT_SECONDS + "s.");
            return DEFAULT_WAIT_SECONDS * 1000L;
        }
    }

    /**
     * Blocks until this boot holds the migration mutex, or the wait budget runs out.
     *
     * @return the held mutex; never null (failure throws, so a caller cannot mistake "gave up" for
     *         "acquired" and migrate anyway)
     */
    static Held acquire(DataSource dataSource) {
        long budgetMillis = waitMillis();
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            // SqlDialects.forConnection, NOT SqlDialects.active(). active() answers "what engine was
            // this app CONFIGURED for" and DEFAULTS TO POSTGRES when nothing pinned it -- so on an H2
            // app that never set npdev.storage.dialect it reports Postgres, this code would believe
            // it had SESSION_ADVISORY_LOCK, and pg_try_advisory_lock would hit H2 as a syntax error
            // on the very first boot. Measured: 10 RuntimeHost tests failed exactly that way before
            // this line changed. The mechanism a lock uses has to come from the database actually on
            // the other end of the socket, which is the distinction forConnection exists to draw and
            // the reason the pre-R9.3 code read getDatabaseProductName() by hand here.
            SqlDialect dialect = SqlDialects.forConnection(connection);
            boolean advisory = dialect.supports(StorageCapability.SESSION_ADVISORY_LOCK);
            if (!advisory) {
                prepareFallbackTable(connection, dialect);
                connection.setAutoCommit(false);
            }
            boolean waited = false;
            while (true) {
                if (advisory ? tryAdvisory(connection, dialect) : tryRowLock(connection, dialect)) {
                    if (waited) {
                        System.out.println("NPDev schema lifecycle: another instance was migrating this database; "
                                + "waited for it and acquired the migration lock -- continuing normally.");
                    }
                    Held held = new Held(connection, dialect, advisory);
                    connection = null; // ownership transfers to the caller's release()
                    return held;
                }
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(waitedOutMessage(budgetMillis, dataSource));
                }
                waited = true;
                Thread.sleep(POLL_MILLIS);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to acquire the migration lock: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the migration lock", interrupted);
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * Releases the mutex. Closing the connection is what actually releases it on every engine -- the
     * explicit unlock/rollback first is belt and braces, keeping the release independent of exactly
     * when the driver tears the socket down. Never throws: the migration this protected has already
     * finished by the time this runs, so a cleanup failure must not become the boot's error.
     */
    static void release(Held held) {
        if (held == null) {
            return;
        }
        try {
            if (held.advisory()) {
                SqlDialect dialect = held.dialect();
                try (PreparedStatement statement =
                        held.connection().prepareStatement(dialect.releaseAdvisoryLockSql())) {
                    statement.setObject(1, dialect.advisoryLockKey(LOCK_NAME));
                    statement.execute();
                }
            } else {
                held.connection().rollback();
            }
        } catch (SQLException | RuntimeException failure) {
            System.out.println("NPDev schema lifecycle: releasing the migration lock explicitly failed ("
                    + failure.getMessage() + "); closing its connection below still releases it, since the lock "
                    + "is scoped to that connection on every engine.");
        } finally {
            closeQuietly(held.connection());
        }
    }

    private static boolean tryAdvisory(Connection connection, SqlDialect dialect) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.tryAdvisoryLockSql())) {
            statement.setObject(1, dialect.advisoryLockKey(LOCK_NAME));
            try (ResultSet resultSet = statement.executeQuery()) {
                // REG-91's shape: an empty result set here means the statement did not do what this
                // code assumes, which is worth saying rather than reading back as "not acquired" and
                // waiting out the whole budget for a lock that was never contended.
                if (!resultSet.next()) {
                    throw new SQLException("the advisory-lock probe returned no rows, so the migration lock "
                            + "cannot be taken on this connection (engine " + dialect.name() + ").");
                }
                return resultSet.getInt(1) == 1;
            }
        }
    }

    /**
     * The no-advisory-lock path. Zero rows means another transaction holds the row -- SKIP LOCKED
     * reports contention by returning nothing rather than by blocking or throwing, which is what
     * makes this probe non-blocking without any lock-timeout setting.
     */
    private static boolean tryRowLock(Connection connection, SqlDialect dialect) throws SQLException {
        dialect.require(StorageCapability.SKIP_LOCKED_READS);
        String sql = dialect.selectForUpdateSkipLocked(
                "lock_name", FALLBACK_TABLE, "lock_name = ?", "lock_name", 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        // Leave no transaction open around a failed probe: on an engine that snapshots at first
        // read, keeping it would pin this connection's view and the next poll would re-read the same
        // stale answer forever.
        connection.rollback();
        return false;
    }

    /**
     * Bootstraps the fallback lock's schema, table and row -- retrying, because <b>{@code IF NOT
     * EXISTS} is not atomic</b>.
     *
     * <p>Measured on H2 2.2.224 by this feature's own two-thread test: two boots bootstrapping at
     * the same instant race INSIDE the guard, and the loser's statement fails with
     * <pre>
     *   General error: "java.lang.RuntimeException: object already exists"; SQL statement:
     *   CREATE SCHEMA IF NOT EXISTS npdev_lock [50000-232]
     * </pre>
     * The guard suppresses the error you get from creating an object that already existed BEFORE the
     * statement started; it does nothing about one that appears WHILE it runs. That window is a few
     * microseconds wide and no sequential test can reach it -- which is the argument for the
     * two-thread test, since this is a first-boot-only path and a first boot happens once.
     *
     * <p>Retrying is the whole fix: on the next pass the object is unambiguously present, so the
     * guard genuinely no-ops. Deliberately NOT solved with a {@code synchronized} block -- that would
     * make this JVM's two threads orderly while leaving two PROCESSES (the case that actually
     * matters, and the one an H2Server app really has) racing exactly as before, and it would take
     * the teeth out of the test that found this.
     */
    private static void prepareFallbackTable(Connection connection, SqlDialect dialect) throws SQLException {
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                bootstrapFallbackTable(connection, dialect);
                return;
            } catch (SQLException racedFailure) {
                lastFailure = racedFailure;
            }
        }
        throw new SQLException("could not bootstrap the migration lock table " + FALLBACK_TABLE
                + " after 3 attempts. A concurrent first boot creating it at the same instant is retried "
                + "(and normally succeeds on the second pass), so a failure this persistent is not a race "
                + "-- the driver's own message is attached.", lastFailure.getSQLState(),
                lastFailure.getErrorCode(), lastFailure);
    }

    private static void bootstrapFallbackTable(Connection connection, SqlDialect dialect) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.guardedCreateSchema(FALLBACK_SCHEMA))) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                dialect.guardedCreateTable(FALLBACK_TABLE,
                        "CREATE TABLE " + FALLBACK_TABLE
                                // dialect.keyableTextColumnType(), not InternalDdlTypes.keyText():
                                // the latter reads SqlDialects.active(), which is the app-config
                                // answer this method has just deliberately stopped trusting.
                                + " (lock_name " + dialect.keyableTextColumnType() + " PRIMARY KEY)"))) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + FALLBACK_TABLE + " (lock_name) VALUES (?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeUpdate();
        } catch (SQLException failure) {
            if (!dialect.isUniqueViolation(failure)) {
                // Anything but "the row is already there" leaves the table without the row this
                // mechanism locks, and a missing row reads as permanent contention -- REG-91's
                // lesson: say which of the two it is, rather than timing out with the wrong story.
                throw failure;
            }
        }
    }

    /**
     * The give-up diagnostic. Names the holder from the human-readable claim row when there is one,
     * and is explicit that the mutex is connection-scoped -- so an operator reading this knows a
     * crashed instance cannot be the cause and looks for a live, hung one instead.
     */
    private static String waitedOutMessage(long budgetMillis, DataSource dataSource) {
        String holder = MigrationClaimStore.current(dataSource)
                .map(claim -> "instance " + claim.instanceId() + " on host " + claim.hostname()
                        + ", claimed at epoch-ms " + claim.claimedAtUtc())
                .orElse("an instance that did not record a readable claim row");
        // The "Another NPDev instance is currently migrating..." sentence is matched VERBATIM by
        // NPDevCli's boot-log classifier (npdev_cli.py's MIGRATION_CLAIM_HELD diagnostic greps this
        // exact phrase as a substring), so it is kept word for word even though the surrounding
        // explanation changed: rewording it silently downgrades a named diagnostic to "unknown boot
        // failure" with nothing to notice it. The "B4:migration_lock_held:" prefix (2026-08-25 W2.3,
        // docs/ACCEPTED_BOUNDARIES.md) is safe alongside it -- a leading prefix does not break a
        // substring `in` check -- and lets a boot log line or future orchestrator hook key on this
        // specific boundary rather than parsing English.
        return "B4:migration_lock_held:Another NPDev instance is currently migrating this database (" + holder + "), and this boot "
                + "timed out after " + (budgetMillis / 1000L) + "s waiting for it. Concurrent boots serialize "
                + "on a migration lock rather than refusing, so waiting the full budget means the other "
                + "migration is still running or its process is hung -- the lock is scoped to that instance's "
                + "database connection, so a CRASHED instance would already have released it and this boot "
                + "would have proceeded. Let the other migration finish and retry, raise the budget with -D"
                + WAIT_SECONDS_PROPERTY + "=<seconds>, or kill the hung instance. See "
                + "docs/SCHEMA_EVOLUTION.md#collision-detection.";
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
}
