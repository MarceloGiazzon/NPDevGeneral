package com.npdev.adapters.flowinstance.jdbc;

import com.npdev.kernel.execution.FlowInstance;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8c (RUN-2): {@link JdbcFlowInstanceStore#claimWaitingEligibleToResume} is what makes two
 * resumer instances polling ONE database safe against double-resuming the same flow instance.
 * Proves it against a real H2 database (this session's local test-cadence policy; the real
 * Postgres/MySQL/SqlServer matrix runs per PR in CI) at three levels:
 *
 * <ul>
 *   <li>{@code sequentialClaimsPartitionTheEligibleRows} -- the WHERE-clause exclusion
 *       ({@code claimed_until IS NULL OR claimed_until < now}) alone, no real concurrency needed.</li>
 *   <li>{@code expiredClaimBecomesReclaimable} -- the crash-recovery lease: a claimant that never
 *       comes back does not permanently strand its batch.</li>
 *   <li>{@code concurrentClaimsFromTwoRealThreadsNeverOverlap} -- the actual guarantee RUN-2 names:
 *       two independent connections racing {@code SELECT ... FOR UPDATE SKIP LOCKED} against the
 *       SAME live database never both walk away with the same row.</li>
 * </ul>
 */
class JdbcFlowInstanceStoreClaimTest {

    private static final String[] SCHEMA_SQL = new String[]{
            """
            CREATE TABLE npdev_flow_instance (
                execution_id VARCHAR(191) NOT NULL,
                flow_name TEXT NOT NULL,
                correlation_id TEXT NOT NULL,
                tenant_id TEXT,
                actor_id TEXT,
                status TEXT NOT NULL,
                current_step_index INT NOT NULL,
                waiting_for_event_name TEXT,
                state_json TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL,
                resume_attempt_count INT NOT NULL DEFAULT 0,
                last_resume_at TIMESTAMP,
                last_resume_error_code TEXT,
                next_eligible_resume_at TIMESTAMP,
                last_progress_at TIMESTAMP,
                last_error_kind TEXT,
                last_error_code TEXT,
                last_error_message TEXT,
                failed_at TIMESTAMP,
                claimed_by TEXT,
                claimed_until TIMESTAMP,
                PRIMARY KEY (execution_id)
            )
            """,
            "CREATE INDEX idx_inst_tenant_status_claimed ON npdev_flow_instance (tenant_id, status, claimed_until)"
    };

    private DataSource dataSource;
    private JdbcFlowInstanceStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : SCHEMA_SQL) {
                statement.execute(sql);
            }
        }
        store = new JdbcFlowInstanceStore(dataSource, new com.fasterxml.jackson.databind.ObjectMapper(), H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    private void seedWaiting(String tenantId, int count) {
        for (int i = 0; i < count; i++) {
            FlowInstance instance = FlowInstance
                    .start("exec-" + i, "TestFlow", "corr-" + i, tenantId, "actor-a", java.util.Map.of(), 1_000L)
                    .markWaiting(0, "SomeEvent", java.util.Map.of(), 1_000L);
            store.save(instance);
        }
    }

    /**
     * The RED case RUN-2 describes, reproduced against CURRENT production code rather than assumed:
     * {@link JdbcFlowInstanceStore#findWaitingEligibleToResume} -- what {@code ResumeCoordinator}
     * called before this fix, and what {@link #claimWaitingEligibleToResume}'s OWN default
     * implementation on the {@code FlowInstanceStore} interface still delegates to for any store
     * that has not opted in -- has no claiming semantics at all. Two independent callers reading the
     * SAME eligible set get back the IDENTICAL rows, which is exactly how two resumer instances would
     * have both called {@code resumeExecution} on the same flow instance. The other three tests in
     * this class prove {@code claimWaitingEligibleToResume} closes exactly this gap.
     */
    @Test
    void withoutClaimingConcurrentReadsWouldSeeTheSameRowsTwice() {
        seedWaiting("tenant-a", 3);
        List<FlowInstance> readByA = store.findWaitingEligibleToResume("tenant-a", 2_000L, 10);
        List<FlowInstance> readByB = store.findWaitingEligibleToResume("tenant-a", 2_000L, 10);
        Set<String> idsA = readByA.stream().map(FlowInstance::executionId).collect(Collectors.toSet());
        Set<String> idsB = readByB.stream().map(FlowInstance::executionId).collect(Collectors.toSet());
        assertEquals(idsA, idsB, "the plain read has no claim semantics -- two callers see the SAME "
                + "rows, which is the exact double-resume risk claimWaitingEligibleToResume closes");
        assertEquals(3, idsA.size());
    }

    @Test
    void sequentialClaimsPartitionTheEligibleRows() {
        seedWaiting("tenant-a", 5);

        List<FlowInstance> claimedByA = store.claimWaitingEligibleToResume("tenant-a", 2_000L, 60_000L, "claimant-A", 3);
        assertEquals(3, claimedByA.size(), claimedByA.toString());

        List<FlowInstance> claimedByB = store.claimWaitingEligibleToResume("tenant-a", 2_000L, 60_000L, "claimant-B", 10);
        assertEquals(2, claimedByB.size(), claimedByB.toString());

        Set<String> idsA = claimedByA.stream().map(FlowInstance::executionId).collect(Collectors.toSet());
        Set<String> idsB = claimedByB.stream().map(FlowInstance::executionId).collect(Collectors.toSet());
        assertTrue(Collections.disjoint(idsA, idsB), "A and B must never claim the same row: A=" + idsA + " B=" + idsB);
        assertEquals(5, idsA.size() + idsB.size(), "every eligible row must be claimed by exactly one of A/B");

        // A third claim attempt, even with room for more, finds nothing left -- everything is held.
        List<FlowInstance> claimedByC = store.claimWaitingEligibleToResume("tenant-a", 2_000L, 60_000L, "claimant-C", 10);
        assertTrue(claimedByC.isEmpty(), claimedByC.toString());
    }

    @Test
    void expiredClaimBecomesReclaimable() {
        seedWaiting("tenant-a", 2);

        List<FlowInstance> claimedByA = store.claimWaitingEligibleToResume("tenant-a", 2_000L, 1_000L, "claimant-A", 10);
        assertEquals(2, claimedByA.size());

        // Still within the 1s lease (claimed_until = 3000): nothing is claimable.
        List<FlowInstance> tooSoon = store.claimWaitingEligibleToResume("tenant-a", 2_500L, 1_000L, "claimant-B", 10);
        assertTrue(tooSoon.isEmpty(), tooSoon.toString());

        // Past the lease expiry: claimant-A never came back (crashed mid-resume), so claimant-B
        // must be able to pick the same rows back up -- the crash-recovery guarantee.
        List<FlowInstance> afterExpiry = store.claimWaitingEligibleToResume("tenant-a", 5_000L, 60_000L, "claimant-B", 10);
        assertEquals(2, afterExpiry.size(), afterExpiry.toString());
    }

    @Test
    void concurrentClaimsFromTwoRealThreadsNeverOverlap() throws Exception {
        int totalRows = 12;
        seedWaiting("tenant-a", totalRows);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<List<FlowInstance>>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                String claimant = "thread-" + t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return store.claimWaitingEligibleToResume("tenant-a", 2_000L, 60_000L, claimant, 7);
                }));
            }
            // Release both threads as close to simultaneously as this JVM can manage -- the point
            // is to actually exercise SELECT ... FOR UPDATE SKIP LOCKED under real overlapping
            // transactions, not merely two calls that happen to run one after the other.
            startGate.countDown();

            List<FlowInstance> resultA = futures.get(0).get(30, TimeUnit.SECONDS);
            List<FlowInstance> resultB = futures.get(1).get(30, TimeUnit.SECONDS);

            Set<String> idsA = resultA.stream().map(FlowInstance::executionId).collect(Collectors.toSet());
            Set<String> idsB = resultB.stream().map(FlowInstance::executionId).collect(Collectors.toSet());

            assertEquals(resultA.size(), idsA.size(), "thread A's own result must not contain duplicates");
            assertEquals(resultB.size(), idsB.size(), "thread B's own result must not contain duplicates");
            assertTrue(Collections.disjoint(idsA, idsB),
                    "R8c/RUN-2: two concurrent claimants must NEVER both claim the same flow instance -- "
                            + "A=" + idsA + " B=" + idsB);
            assertTrue(idsA.size() + idsB.size() <= totalRows,
                    "the two claimants together can never exceed what actually exists -- A=" + idsA + " B=" + idsB);

            // Mop-up, matching real production semantics: ResumeSchedulerRunner polls every 2s and
            // simply tries again, so "did the concurrent pass account for every row in one instant"
            // is not itself the guarantee -- "was any row EVER claimed twice" is. Under real
            // contention (this class's own multiple isolated runs showed 12/12 most of the time, and
            // occasionally fewer than 12 from the two concurrent calls alone -- SKIP LOCKED's
            // "continue scanning past a locked row" behavior is not identically implemented on every
            // engine, and H2 2.2.220's is the newest/least battle-tested of the four; see
            // DialectTestSupport's own "local H2 is the fast signal, the CI container is the true
            // one" caveat), a THIRD claim call catches whatever the two overlapping transactions
            // between them left behind.
            List<FlowInstance> mopUp = store.claimWaitingEligibleToResume("tenant-a", 2_000L, 60_000L, "mop-up", totalRows);
            Set<String> idsMopUp = mopUp.stream().map(FlowInstance::executionId).collect(Collectors.toSet());
            assertTrue(Collections.disjoint(idsMopUp, idsA), "mop-up must not re-claim anything A already holds");
            assertTrue(Collections.disjoint(idsMopUp, idsB), "mop-up must not re-claim anything B already holds");

            Set<String> allClaimed = new java.util.LinkedHashSet<>(idsA);
            allClaimed.addAll(idsB);
            allClaimed.addAll(idsMopUp);
            assertEquals(totalRows, allClaimed.size(),
                    "across the concurrent pass plus its mop-up, every eligible row is claimed EXACTLY once -- "
                            + "A=" + idsA + " B=" + idsB + " mopUp=" + idsMopUp);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific
     *  compile-time dependency at the interface level (mirrors the RuntimeHost tests' own helper). */
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
