package com.finalexec.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9.3: two genuinely simultaneous boots against ONE fresh database must serialize.
 *
 * <h2>Why this test is two threads and not two calls</h2>
 *
 * <p>Its sibling {@code SchemaLifecycleExecutorMigrationClaimTest} says so itself: <i>"a real
 * concurrent-insert race is not (and cannot be) proven by a single-threaded H2 test; only 'a held
 * claim is detected and a released claim is gone' is asserted here"</i>. That was an honest limit on
 * what detect-and-refuse could be shown to do, and it is exactly the limit R9.3 has to lift --
 * calling the lock twice in a row proves the second call sees the first one's bookkeeping, which is
 * true of a mechanism with no mutual exclusion whatsoever. So every assertion here comes from two
 * real threads released together by a {@link CountDownLatch}.
 *
 * <h2>The control</h2>
 *
 * <p>{@link #controlUnlockedSectionsDoOverlapSoTheDetectorWorks} runs the SAME harness with the lock
 * removed and asserts the overlap detector FIRES. Without it, {@link
 * #concurrentClaimsAdmitOneInstanceAtATime} would pass just as happily against a broken lock, a
 * no-op lock, or a harness whose threads never actually ran at the same time -- the failure mode
 * that makes a green concurrency test worthless.
 *
 * <p><b>H2 only, deliberately.</b> {@code scripts/policy/local-test-profile.json} disables Postgres
 * and MySQL on a developer machine, and H2 is the engine that exercises the interesting half of
 * R9.3 anyway: it is the one engine with no session advisory lock, so it takes the row-lock fallback
 * in a dedicated schema. Postgres, MySQL and SQL Server take the native advisory-lock path, which
 * CI's engine matrix covers.
 */
class MigrationLockConcurrentBootTest {

    /** Long enough that two unlocked sections cannot miss each other, short enough to stay fast. */
    private static final long CRITICAL_SECTION_MILLIS = 300L;

    private static final String WAIT_SECONDS_PROPERTY = "npdev.schema.lock.waitSeconds";

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;
    private String previousWaitSeconds;

    @BeforeEach
    void setUp() {
        // DB_CLOSE_DELAY=-1 keeps the in-memory database alive between connections, so both threads
        // and both boots address ONE database rather than each getting a private empty one.
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        previousWaitSeconds = System.getProperty(WAIT_SECONDS_PROPERTY);
        System.setProperty(WAIT_SECONDS_PROPERTY, "30");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (previousWaitSeconds == null) {
            System.clearProperty(WAIT_SECONDS_PROPERTY);
        } else {
            System.setProperty(WAIT_SECONDS_PROPERTY, previousWaitSeconds);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    // ------------------------------------------------------------------ the lock itself

    @Test
    @DisplayName("R9.3: two threads claiming a FRESH database's migration lock never hold it at once")
    void concurrentClaimsAdmitOneInstanceAtATime() throws Exception {
        Occupancy occupancy = runTwoThreads(true);

        assertEquals(1, occupancy.peak(),
                "two boots were inside the migration window at the same time -- the lock did not serialize them");
        assertEquals(2, occupancy.entries(),
                "both boots must eventually get in: serializing means WAITING, not refusing one of them");
        assertTrue(occupancy.failures().isEmpty(), "no boot may fail; they must queue: " + occupancy.failures());
    }

    @Test
    @DisplayName("CONTROL: with the lock removed the same harness DOES observe two boots overlapping")
    void controlUnlockedSectionsDoOverlapSoTheDetectorWorks() throws Exception {
        Occupancy occupancy = runTwoThreads(false);

        // If this ever reports 1, the test above is proving nothing -- the threads were not
        // concurrent and the lock was never the reason they did not overlap.
        assertEquals(2, occupancy.peak(),
                "the unlocked control must observe both threads inside the window at once; if it does not, "
                        + "the harness is not actually running them concurrently and the locked assertion is vacuous");
        assertEquals(2, occupancy.entries(), occupancy.failures().toString());
    }

    /**
     * Both threads take (or skip) the real {@link MigrationClaimStore#claim} against a fresh
     * database -- the window that had no lock at all before R9.3 on every engine without a session
     * advisory lock -- and record when they enter and leave it.
     *
     * @param locked false reproduces the pre-R9.3 fresh-boot path, which simply did not lock
     */
    private Occupancy runTwoThreads(boolean locked) throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger entries = new AtomicInteger();
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                MigrationClaimStore.Claim claim = null;
                try {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    if (locked) {
                        claim = MigrationClaimStore.claim(dataSource, true);
                    }
                    entries.incrementAndGet();
                    peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    // Hold the window open long enough that a second unlocked entrant is certain to
                    // land inside it. This is what makes the control deterministic rather than a
                    // race the scheduler might hide.
                    Thread.sleep(CRITICAL_SECTION_MILLIS);
                    inside.decrementAndGet();
                } catch (Exception failure) {
                    failures.add(failure.toString());
                } finally {
                    if (claim != null) {
                        MigrationClaimStore.release(dataSource, claim.instanceId());
                    }
                }
            }, "migration-lock-" + i);
            thread.start();
            threads.add(thread);
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "both threads must reach the start line");
        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            assertFalse(thread.isAlive(), "thread " + thread.getName() + " never finished -- the lock deadlocked");
        }
        return new Occupancy(peak.get(), entries.get(), failures);
    }

    private record Occupancy(int peak, int entries, List<String> failures) {
    }

    // ------------------------------------------------------------------ the real boot path

    @Test
    @DisplayName("R9.3: two simultaneous FIRST-EVER boots against one fresh database both complete")
    void simultaneousFirstEverBootsBothComplete(@TempDir Path migrations) throws Exception {
        // A REAL migration, not the empty locations the single-boot sibling tests use. Serializing
        // means the SECOND boot is by definition no longer a first boot: it finds the fingerprint the
        // first one stored, so it takes the schema-ahead detector's path and verifies that the tables
        // this build declares actually exist. With nothing to migrate they would not, and the second
        // boot would refuse with "widgets (entire table missing)" -- a correct refusal about a test
        // fixture that never created the table, which would look exactly like a lock failure.
        Files.writeString(migrations.resolve("V1__init.sql"),
                "CREATE TABLE IF NOT EXISTS widgets (id BIGINT PRIMARY KEY);\n", StandardCharsets.UTF_8);
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:first-ever");
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                // A Flyway instance is single-boot-use (see SchemaLifecycleExecutor's wiring), so
                // each simulated instance builds its own over the SAME migration directory, exactly
                // as two real processes booting the same build would.
                Flyway flyway = Flyway.configure()
                        .dataSource(dataSource)
                        .locations("filesystem:" + migrations.toAbsolutePath())
                        .load();
                try {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    executor.migrate(flyway, manifest);
                } catch (Exception failure) {
                    failures.add(failure.toString());
                }
            }, "boot-" + i);
            thread.start();
            threads.add(thread);
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(120));
            assertFalse(thread.isAlive(), "boot " + thread.getName() + " never finished");
        }

        // Serializing means BOTH boots succeed -- the pre-R9.3 mechanism would have killed one of
        // them (on an engine where it engaged at all), which is availability lost to protect data
        // that a lock protects without losing it.
        assertTrue(failures.isEmpty(), "both simultaneous boots must complete: " + failures);
        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(),
                "neither boot may leave the migration slot recorded as held");
    }

    // ------------------------------------------------------------------ REG-7.2 must stay fixed

    @Test
    @DisplayName("REG-7.2 stays fixed: locking a virgin database adds nothing to Flyway's own schema")
    void lockingAVirginDatabaseLeavesFlywaysSchemaEmpty(@TempDir Path migrations) throws Exception {
        // A REAL migration location, not the empty one the sibling tests use: Flyway's "Found
        // non-empty schema(s) 'PUBLIC' but no schema history table" check is what REG-7.2 tripped,
        // and with nothing to migrate there is nothing for that check to refuse. This is the setup
        // in which self-bootstrapping a lock table into PUBLIC would fail the boot.
        Files.writeString(migrations.resolve("V1__init.sql"),
                "CREATE TABLE IF NOT EXISTS widgets (id BIGINT PRIMARY KEY);\n", StandardCharsets.UTF_8);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations.toAbsolutePath())
                .load();

        executor.migrate(flyway, manifestIdOnly("sha256:first-ever"));

        assertTrue(tableExistsInSchema("WIDGETS", "PUBLIC"), "the migration itself must have run");
        assertFalse(tableExistsInSchema(MigrationClaimStore.TABLE.toUpperCase(java.util.Locale.ROOT), "PUBLIC"),
                "a first-ever boot must not self-bootstrap the claim table into Flyway's schema -- that is REG-7.2");
        // ...while the lock it DID take lives somewhere Flyway never looks.
        assertTrue(tableExistsInSchema("NPDEV_MIGRATION_MUTEX", MigrationMutex.FALLBACK_SCHEMA.toUpperCase(java.util.Locale.ROOT)),
                "the fresh-boot lock must exist, in its own schema -- otherwise this boot was never locked at all");
    }

    @Test
    @DisplayName("R9.3: a crashed instance's leftover claim row does not block the next boot")
    void aStaleHolderRowDoesNotBlockTheNextBoot() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            MigrationClaimStore.ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + MigrationClaimStore.TABLE
                            + " (claim_key, instance_id, hostname, claimed_at_utc) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, "schema-migration");
                statement.setString(2, "crashed-instance");
                statement.setString(3, "some-host");
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }

        // No mutex is held -- the "crashed" instance's connection is gone, which is the whole point
        // of a connection-scoped lock. Before R9.3 this row alone refused every future boot until an
        // operator ran clear-claim by hand.
        long startedAt = System.nanoTime();
        MigrationClaimStore.Claim claim = MigrationClaimStore.claim(dataSource, false);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(claim != null, "a stale holder row must not refuse the boot");
        assertTrue(elapsedMillis < 5_000L, "the boot must proceed immediately, not wait out the lock budget "
                + "(took " + elapsedMillis + "ms)");
        MigrationClaimStore.release(dataSource, claim.instanceId());
    }

    // ------------------------------------------------------------------ helpers

    private boolean tableExistsInSchema(String table, String schema) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE UPPER(TABLE_NAME) = ? AND UPPER(TABLE_SCHEMA) = ?")) {
            statement.setString(1, table);
            statement.setString(2, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestIdOnly(String toFingerprint) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- a NEW physical connection per call,
     *  which is what makes the two threads genuinely independent sessions. */
    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
