package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3.2 (B4 migrate-only + progress-aware waiting): {@link MigrationMutex#acquire}'s adaptive-budget
 * extension. {@code npdev.schema.lock.waitSeconds} stays the FLOOR (unchanged from before this
 * package -- see {@code MigrationLockConcurrentBootTest} and
 * {@code SchemaLifecycleExecutorMigrationClaimTest} for the plain-refusal case with zero recorded
 * activity, both still green and untouched by this addition); this class proves the NEW behavior on
 * top of it: a waiter whose budget expires while {@code npdev_schema_history} shows activity that
 * postdates the wait's own start extends rather than refusing.
 *
 * <p>Talks to {@link SchemaHistoryStore}'s package-private {@code insertRawHistoryRow} directly to
 * simulate a step pass's write-before-execute row, rather than driving a real rename/backfill pass --
 * the wait loop only ever reads the row's existence and timestamp, not what produced it.
 */
class MigrationMutexProgressAwareWaitTest {

    private static final String WAIT_SECONDS_PROPERTY = "npdev.schema.lock.waitSeconds";

    private DataSource dataSource;
    private String previousWaitSeconds;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        previousWaitSeconds = System.getProperty(WAIT_SECONDS_PROPERTY);
        System.setProperty(WAIT_SECONDS_PROPERTY, "1");
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

    @Test
    @DisplayName("a waiter whose budget expires while new activity is recorded extends instead of refusing")
    void extendsPastTheOriginalBudgetWhenNewActivityIsObserved() throws Exception {
        MigrationMutex.Held holder = MigrationMutex.acquire(dataSource);

        AtomicReference<MigrationMutex.Held> waiterResult = new AtomicReference<>();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        CountDownLatch waiterDone = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                waiterResult.set(MigrationMutex.acquire(dataSource));
            } catch (Throwable failure) {
                waiterFailure.set(failure);
            } finally {
                waiterDone.countDown();
            }
        }, "progress-aware-waiter");
        waiter.start();

        // Well before the 1s floor, record activity postdating the wait's own start -- the exact
        // write-before-execute shape recordStepPass uses for a real pass.
        Thread.sleep(300L);
        SchemaHistoryStore.insertRawHistoryRow(
                dataSource, "sha256:from", "sha256:to", "TEST_STEP", List.of("t1"), "PARTIAL-CRASH");

        // Past the ORIGINAL 1s deadline: if extension did not fire, the waiter would already have
        // thrown by now.
        assertFalse(waiterDone.await(1300L, TimeUnit.MILLISECONDS),
                "the waiter gave up at (or before) its original budget despite new activity being recorded "
                        + "during the wait -- the extension did not fire");
        assertNull(waiterFailure.get(), "the waiter must not have failed yet: " + waiterFailure.get());

        MigrationMutex.release(holder);
        assertTrue(waiterDone.await(10, TimeUnit.SECONDS), "the waiter must acquire once the holder releases");
        assertNull(waiterFailure.get(), "the waiter must succeed, not time out: " + waiterFailure.get());
        assertNotNull(waiterResult.get());
        MigrationMutex.release(waiterResult.get());
    }

    @Test
    @DisplayName("a waiter facing no recorded activity still refuses at the configured floor")
    void refusesAtTheFloorWhenNoActivityIsEverRecorded() throws Exception {
        MigrationMutex.Held holder = MigrationMutex.acquire(dataSource);
        try {
            long startedAt = System.nanoTime();
            IllegalStateException failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalStateException.class, () -> MigrationMutex.acquire(dataSource));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(elapsedMillis < 3_000L,
                    "with nothing ever recorded there is no progress to extend on -- must refuse at the "
                            + "configured 1s floor, not hang: took " + elapsedMillis + "ms");
            assertTrue(failure.getMessage().contains("B4:migration_lock_held:"), failure.getMessage());
            assertTrue(failure.getMessage().contains("No schema-change activity has been recorded"),
                    failure.getMessage());
        } finally {
            MigrationMutex.release(holder);
        }
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- a NEW physical connection per call,
     *  matching {@code MigrationLockConcurrentBootTest}'s own copy so both threads and both boots
     *  address one shared in-memory database rather than each getting a private empty one. */
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
