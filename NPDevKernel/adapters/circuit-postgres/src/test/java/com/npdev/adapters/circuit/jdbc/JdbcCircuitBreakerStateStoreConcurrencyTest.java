package com.npdev.adapters.circuit.jdbc;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-37 on the JDBC store, against a real database engine that really takes row locks.
 *
 * <p>Runs on H2 rather than Postgres so it is part of GATE-KERNEL, which has no Docker -- an
 * atomicity guarantee that is only checked when someone remembers to start a container is not much of
 * a guarantee. {@code PostgresCircuitBreakerStateStoreTest} re-proves the same contract on the engine
 * that actually ships. H2 in MVStore mode honours {@code SELECT ... FOR UPDATE}, which is the whole
 * mechanism under test.</p>
 */
class JdbcCircuitBreakerStateStoreConcurrencyTest {

    private DataSource dataSource;
    private JdbcCircuitBreakerStateStore store;

    @BeforeEach
    void setUp() throws SQLException {
        // A shared named in-memory database, NOT a single shared Connection: concurrent transactions
        // need their own connections or the locking being tested is trivially serialised by the driver.
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE npdev_circuit_breaker (
                        tenant_id VARCHAR(200) NOT NULL,
                        capability VARCHAR(200) NOT NULL,
                        operation VARCHAR(200) NOT NULL,
                        state VARCHAR(32) NOT NULL,
                        consecutive_failures INTEGER NOT NULL DEFAULT 0,
                        opened_at_ms BIGINT NOT NULL DEFAULT 0,
                        last_failure_at_ms BIGINT NOT NULL DEFAULT 0,
                        half_open_allowed_at_ms BIGINT NOT NULL DEFAULT 0,
                        half_open_trial_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (tenant_id, capability, operation)
                    )
                    """);
        }
        store = new JdbcCircuitBreakerStateStore(dataSource);
    }

    @Test
    void concurrentFailuresAreCountedExactly() throws Exception {
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "persistence", "save");

        int threads = 8;
        int perThread = 25;
        recordConcurrently(key, threads, perThread, Integer.MAX_VALUE);

        assertEquals(threads * perThread, store.get(key).consecutiveFailures(),
                "every concurrent failure must be counted -- an undercount opens the breaker late");
        assertEquals(CircuitState.CLOSED, store.get(key).state(),
                "threshold was Integer.MAX_VALUE, so the circuit must not have opened");
    }

    @Test
    void concurrentFirstFailuresDoNotRaceOnTheMissingRow() throws Exception {
        // The seed-then-lock step exists for exactly this: SELECT ... FOR UPDATE cannot lock a row
        // that is not there, so without the seed two concurrent FIRST failures would both compute 1.
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "mail", "send");

        recordConcurrently(key, 8, 1, Integer.MAX_VALUE);

        assertEquals(8, store.get(key).consecutiveFailures(),
                "the very first burst against an unseen key must count too");
    }

    @Test
    void theCircuitOpensOnceTheThresholdIsReachedUnderConcurrency() throws Exception {
        // The counter being exact is only useful if the OPEN decision follows it, and that decision
        // is now made inside the store's critical section rather than by the caller.
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "webhook", "post");

        recordConcurrently(key, 4, 5, 10);

        CircuitBreakerState state = store.get(key);
        assertEquals(CircuitState.OPEN, state.state(), "20 failures against a threshold of 10 must open the circuit");
        assertEquals(20, state.consecutiveFailures());
        assertTrue(state.halfOpenAllowedAtMs() > 0, "an open circuit must schedule its half-open retry");
    }

    private void recordConcurrently(CapabilityOpKey key, int threads, int perThread, int threshold) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                running.add(pool.submit(() -> {
                    startTogether.await();
                    for (int i = 0; i < perThread; i++) {
                        store.recordFailure(key, 1_000L + i, threshold, 30_000L);
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<?> task : running) {
                task.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Hands out an independent connection per call -- see the note in setUp(). */
    private record UrlDataSource(String url) implements DataSource {
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
