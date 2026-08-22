package com.finalexec.db;

import com.finalexec.config.SpringTransactionRunner;
import com.npdev.kernel.ports.TransactionRunner;
import com.npdev.kernel.storage.sql.SqlDialects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.3 (roadmap DoD: "two concurrent creates never collide on a live app"): {@link
 * JdbcSequenceAllocator} raced by real threads over real JDBC connections against a real H2
 * database -- the same technique {@code DefaultConceptGatewayRowAuthzRaceTest} (this package) and
 * {@code CronFireClaimStoreTest} (RUN-15) already established for a claim/lock primitive, applied
 * here to an atomic counter increment instead.
 *
 * <p><b>PROVEN RED, and it was not the RED originally planned.</b> The first version of {@link
 * JdbcSequenceAllocator#allocateNext} joined the ambient connection ({@code
 * DataSourceUtils.getConnection}) exactly like {@code JdbcBusinessConceptStore} does and relied on
 * {@code SELECT ... FOR UPDATE}'s lock alone, on the assumption that mirroring an already-proven
 * pattern was enough. Running {@link #concurrentAllocationsWithNoAmbientTransactionNeverCollide()}
 * against that version -- BEFORE this test wrapped each call in a transaction -- measured 20
 * threads x 25 allocations against ONE scope key producing 500 calls but only <b>90 DISTINCT
 * values</b> (a catastrophic collision rate, not a rare edge case). The cause: with no ambient
 * Spring transaction, {@code DataSourceUtils.getConnection} hands back a plain AUTOCOMMIT
 * connection, so the {@code FOR UPDATE} lock is released the instant the {@code SELECT} itself
 * completes (autocommit makes each statement its own transaction) -- the {@code UPDATE} that
 * follows runs completely unprotected. Fixed by having {@link JdbcSequenceAllocator#allocateNext}
 * manage its OWN local transaction whenever the connection it receives is not already inside one
 * (see that method's own javadoc) -- restoring the identical scenario GREEN below, asserted by BOTH
 * tests in this class: one with no ambient transaction at all (proves the class is safe standalone
 * now), one wrapping each call in a real {@code SpringTransactionRunner} transaction (proves the
 * literal roadmap requirement -- allocation running inside the SAME transaction the concept row
 * insert would use).
 */
class JdbcSequenceAllocatorConcurrencyTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SharedUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // Mirrors com.npdev.kernel.dbschema.NpdevSequenceCounterTable's declared shape exactly.
            statement.execute("CREATE TABLE npdev_sequence_counter ("
                    + "scope_key VARCHAR(255) PRIMARY KEY, "
                    + "current_value BIGINT NOT NULL, "
                    + "created_at TIMESTAMP NOT NULL)");
        }
    }

    /** No ambient transaction anywhere -- the exact shape that produced the RED this class's
     *  javadoc documents. Proves {@link JdbcSequenceAllocator} is now safe even called this way. */
    @Test
    void concurrentAllocationsWithNoAmbientTransactionNeverCollide() throws Exception {
        JdbcSequenceAllocator allocator = new JdbcSequenceAllocator(dataSource, SqlDialects.active());
        assertNoCollisionsUnder(20, 25, scopeKey -> () -> allocator.allocateNext(scopeKey), "invoiceNumber|tenant-a|2026");
    }

    /** Each allocation wrapped in its OWN real transaction via {@link SpringTransactionRunner} --
     *  the literal production shape: {@code DefaultConceptGateway.save} wraps its whole
     *  check-then-act body (defaults, including a {@code nextNumber()} allocation, THEN the concept
     *  row insert) in exactly this kind of transaction. Proves the roadmap's own wording: allocation
     *  running inside the SAME gateway transaction as the insert. */
    @Test
    void concurrentAllocationsInsideARealAmbientTransactionNeverCollide() throws Exception {
        JdbcSequenceAllocator allocator = new JdbcSequenceAllocator(dataSource, SqlDialects.active());
        TransactionRunner transactionRunner = new SpringTransactionRunner(new DataSourceTransactionManager(dataSource));
        assertNoCollisionsUnder(20, 25,
                scopeKey -> () -> transactionRunner.runInTransaction(() -> allocator.allocateNext(scopeKey)),
                "orderNumber|tenant-b|2026");
    }

    @FunctionalInterface
    private interface AllocationCallFactory {
        java.util.concurrent.Callable<Long> forScopeKey(String scopeKey);
    }

    private static void assertNoCollisionsUnder(
            int threadCount, int perThread, AllocationCallFactory callFactory, String scopeKey) throws Exception {
        int totalCalls = threadCount * perThread;
        var call = callFactory.forScopeKey(scopeKey);

        Set<Long> allocated = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            var futures = IntStream.range(0, threadCount)
                    .mapToObj(threadIndex -> pool.submit(() -> {
                        assertTrue(start.await(10, TimeUnit.SECONDS), "threads must start together");
                        for (int i = 0; i < perThread; i++) {
                            allocated.add(call.call());
                        }
                        return null;
                    }))
                    .collect(Collectors.toList());
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // The invariant a colliding allocator breaks: exactly totalCalls DISTINCT values, forming
        // precisely {1, ..., totalCalls} -- no duplicate (two threads allocated the same number)
        // and no gap (an increment was lost). Either failure mode is a real collision an ERP
        // document-numbering feature cannot tolerate (two invoices sharing one number).
        assertEquals(totalCalls, allocated.size(),
                "R5.3 DoD: " + threadCount + " threads x " + perThread + " allocations for the SAME scope "
                        + "key must produce " + totalCalls + " DISTINCT numbers -- got " + allocated.size()
                        + " distinct values out of " + totalCalls + " calls (fewer means a collision: two "
                        + "threads allocated the same number)");
        long max = allocated.stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = allocated.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertEquals(1L, min, "the first allocation for a brand-new scope key must be 1");
        assertEquals(totalCalls, max, "the counter must reach exactly " + totalCalls + " with no lost increments");
    }

    @Test
    void distinctScopeKeysAllocateIndependently() {
        JdbcSequenceAllocator allocator = new JdbcSequenceAllocator(dataSource, SqlDialects.active());
        assertEquals(1L, allocator.allocateNext("a"));
        assertEquals(1L, allocator.allocateNext("b"));
        assertEquals(2L, allocator.allocateNext("a"));
        assertEquals(2L, allocator.allocateNext("b"));
        assertEquals(3L, allocator.allocateNext("a"));
    }

    /** Same minimal hand-rolled {@code DataSource} {@code DefaultConceptGatewayRowAuthzRaceTest}
     *  uses -- a shared H2 in-memory URL, one connection per call. */
    private static final class SharedUrlDataSource implements DataSource {
        private final String url;

        private SharedUrlDataSource(String url) {
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
