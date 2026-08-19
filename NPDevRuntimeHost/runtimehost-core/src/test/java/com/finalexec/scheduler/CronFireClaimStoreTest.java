package com.finalexec.scheduler;

import com.npdev.kernel.storage.sql.H2Dialect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2.7: {@link CronFireClaimStore#tryClaim} is what makes two {@link NpdevCronSchedulerService}
 * instances polling ONE database safe against double-firing the SAME (flowName, tenantId,
 * scheduledFireTime) window. Proves it against a real H2 database (local test-cadence policy; the
 * real Postgres/MySQL/SqlServer matrix runs per PR in CI), mirroring
 * {@code JdbcFlowInstanceStoreClaimTest}'s technique for RUN-2's own claim exactly.
 *
 * <p>The end-to-end double-fire RED control (two REAL {@link NpdevCronSchedulerService} instances,
 * run against the code before the claim call existed, actually observed firing the same tick twice)
 * lives in {@link NpdevCronSchedulerServiceMultiInstanceTest} -- see its javadoc for the recorded
 * RED output. This class proves the claim primitive itself in isolation.
 */
class CronFireClaimStoreTest {

    private static final String SCHEMA_SQL = """
            CREATE TABLE npdev_cron_fire_claim (
                flow_name VARCHAR(191) NOT NULL,
                tenant_id VARCHAR(191) NOT NULL,
                scheduled_fire_time TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL,
                claimed_by TEXT,
                claimed_until TIMESTAMP,
                PRIMARY KEY (flow_name, tenant_id, scheduled_fire_time)
            )
            """;

    private DataSource dataSource;
    private CronFireClaimStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA_SQL);
        }
        store = new CronFireClaimStore(dataSource, H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void sequentialClaimForTheSameTickTheSecondCallerLoses() {
        Instant tick = Instant.parse("2026-08-19T02:00:00Z");
        assertTrue(store.tryClaim("CloseStaleOrders", "tenant-a", tick, "claimant-A", 60_000L),
                "the first claimant must win an unclaimed tick");
        assertFalse(store.tryClaim("CloseStaleOrders", "tenant-a", tick, "claimant-B", 60_000L),
                "a second claimant must NOT also win the SAME tick");

        // A different tick for the same flow/tenant is an independent claim.
        Instant nextTick = tick.plusSeconds(1);
        assertTrue(store.tryClaim("CloseStaleOrders", "tenant-a", nextTick, "claimant-B", 60_000L),
                "a DIFFERENT scheduled_fire_time is a fresh, independent claim");
    }

    @Test
    void expiredClaimBecomesReclaimable() {
        Instant tick = Instant.parse("2026-08-19T02:00:00Z");
        assertTrue(store.tryClaim("CloseStaleOrders", "tenant-a", tick, "claimant-A", 500L));

        // Still within the lease: nobody else may claim it.
        assertFalse(store.tryClaim("CloseStaleOrders", "tenant-a", tick, "claimant-B", 60_000L));

        try {
            Thread.sleep(700);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        // Past the lease expiry: claimant-A never came back, so claimant-B may take over.
        assertTrue(store.tryClaim("CloseStaleOrders", "tenant-a", tick, "claimant-B", 60_000L),
                "an expired lease must become reclaimable -- crash recovery");
    }

    /**
     * The actual guarantee R2.7 names: two independent connections racing
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} against the SAME live database for the SAME tick
     * never both walk away claimed.
     */
    @Test
    void concurrentClaimsFromTwoRealThreadsForTheSameTickNeverBothWin() throws Exception {
        Instant tick = Instant.parse("2026-08-19T02:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                String claimant = "thread-" + t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return store.tryClaim("CloseStaleOrders", "tenant-a", tick, claimant, 60_000L);
                }));
            }
            // Release both threads as close to simultaneously as this JVM can manage -- the point is
            // to actually exercise SELECT ... FOR UPDATE SKIP LOCKED under real overlapping
            // transactions, not merely two calls that happen to run one after the other.
            startGate.countDown();

            boolean wonByA = futures.get(0).get(30, TimeUnit.SECONDS);
            boolean wonByB = futures.get(1).get(30, TimeUnit.SECONDS);

            assertTrue(wonByA ^ wonByB,
                    "exactly one of the two concurrent claimants must win the SAME tick -- wonByA=" + wonByA
                            + " wonByB=" + wonByB);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; mirrors
     *  {@code JdbcFlowInstanceStoreClaimTest}'s own helper of the same shape. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
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
