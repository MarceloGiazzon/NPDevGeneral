package com.finalexec.scheduler;

import com.npdev.adapters.flowcompiled.CompiledModelFlowDefinitionProvider;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowSchedule;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2.7's own definition-of-done, proved literally: "two scheduler instances against one H2 database
 * fire a cron flow exactly once per window." Two REAL {@link NpdevCronSchedulerService} instances
 * (own {@link KernelRunner}, own {@link ScheduleOutcomeTracker}, own {@code ThreadPoolTaskScheduler}
 * -- everything a real second replica would have of its own) share ONE H2 {@link DataSource} and race
 * the SAME {@code "* * * * * *"} (every-second) cron for a few seconds.
 *
 * <h2>The invariant this asserts, and why it needs no timing assumptions</h2>
 *
 * <p>{@code npdev_cron_fire_claim} gets exactly one row per DISTINCT (flow, tenant, scheduled_fire_time)
 * either instance ever attempted (the insert step is idempotent under a race -- see
 * {@link CronFireClaimStore#tryClaim}), and the claim guarantees at most one instance ever wins a
 * given row. So regardless of exactly how the two instances' per-second ticks line up: {@code
 * totalFiresAcrossBothTrackers == COUNT(*) FROM npdev_cron_fire_claim} whenever the claim is doing its
 * job, and strictly GREATER whenever it is not (an uncoordinated instance fires every tick it sees,
 * independent of what the other already claimed).
 *
 * <h2>RED, actually observed against the pre-fix code (recorded here, not assumed)</h2>
 *
 * <p>Run with {@code runScheduledFlow} temporarily edited to still call {@code tryClaim} (so the
 * claim-table row count stays a valid "distinct ticks attempted" measurement) but IGNORE its result
 * and never back off -- reproducing the pre-fix, no-coordination behavior (the exact shape
 * {@code NpdevCronSchedulerService} had before this item: fire unconditionally):
 * <pre>
 * org.opentest4j.AssertionFailedError: R2.7 DoD: two scheduler instances against one H2 database
 * must fire each cron window exactly once -- firesA=4 firesB=4 distinctClaimedTicks=4
 * ==&gt; expected: &lt;4&gt; but was: &lt;8&gt;
 * </pre>
 * Exactly the double-fire the item names -- every one of the 4 distinct ticks observed in the window
 * was fired by BOTH instances (8 = 4 * 2), with the claim's back-off removed. Restoring the claim
 * check (this file's current, committed state) reproduces the identical scenario GREEN.
 */
class NpdevCronSchedulerServiceMultiInstanceTest {

    @Test
    void twoInstancesAgainstOneDatabaseFireEachWindowExactlyOnce() throws Exception {
        CompiledModel compiledModel = new CompiledModel(
                "demo",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CompiledFlow(
                        "MultiInstanceCron",
                        "Order",
                        "sync",
                        List.of(new CompiledFlowStep(
                                "return",
                                "return",
                                null,
                                null,
                                List.of(),
                                null,
                                null,
                                "$input",
                                null
                        )),
                        null,
                        null,
                        null,
                        false,
                        new CompiledFlowSchedule("* * * * * *", List.of("tenant-a"))
                )),
                List.of()
        );

        DataSource sharedDataSource = newSharedClaimTableDataSource();

        ScheduleOutcomeTracker trackerA = new ScheduleOutcomeTracker();
        ScheduleOutcomeTracker trackerB = new ScheduleOutcomeTracker();
        NpdevCronSchedulerService instanceA = new NpdevCronSchedulerService(
                compiledModel, newKernelRunner(compiledModel), trackerA, 2, sharedDataSource);
        NpdevCronSchedulerService instanceB = new NpdevCronSchedulerService(
                compiledModel, newKernelRunner(compiledModel), trackerB, 2, sharedDataSource);

        instanceA.start();
        instanceB.start();
        try {
            Thread.sleep(4000);
        } finally {
            instanceA.stop();
            instanceB.stop();
        }
        // Let any in-flight execution triggered right before shutdown finish committing its outcome.
        Thread.sleep(300);

        long firesA = runCount(trackerA, "MultiInstanceCron", "tenant-a");
        long firesB = runCount(trackerB, "MultiInstanceCron", "tenant-a");
        long totalFires = firesA + firesB;
        long distinctClaimedTicks = countClaimRows(sharedDataSource);

        assertTrue(distinctClaimedTicks >= 2,
                "test is only meaningful if multiple distinct ticks actually occurred, got "
                        + distinctClaimedTicks);
        assertEquals(distinctClaimedTicks, totalFires,
                "R2.7 DoD: two scheduler instances against one H2 database must fire each cron window "
                        + "exactly once -- firesA=" + firesA + " firesB=" + firesB
                        + " distinctClaimedTicks=" + distinctClaimedTicks);
    }

    private static long runCount(ScheduleOutcomeTracker tracker, String flowName, String tenantId) {
        return tracker.all().stream()
                .filter(o -> flowName.equals(o.flowName()) && tenantId.equals(o.tenantId()))
                .mapToLong(ScheduleOutcome::runCount)
                .sum();
    }

    private static long countClaimRows(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            var resultSet = statement.executeQuery("SELECT COUNT(*) FROM npdev_cron_fire_claim");
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static KernelRunner newKernelRunner(CompiledModel compiledModel) {
        CapabilityRegistry registry = new CapabilityRegistry();
        return new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                new CompiledModelFlowDefinitionProvider(compiledModel),
                new RegistryCapabilityDispatcher(registry),
                ExecutionTracer.NOOP,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                JsonCodec.noop(),
                (schema, payload) -> List.of()
        );
    }

    private static DataSource newSharedClaimTableDataSource() throws SQLException {
        String url = "jdbc:h2:mem:" + NpdevCronSchedulerServiceMultiInstanceTest.class.getSimpleName()
                + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE npdev_cron_fire_claim (
                        flow_name VARCHAR(191) NOT NULL,
                        tenant_id VARCHAR(191) NOT NULL,
                        scheduled_fire_time TIMESTAMP NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        claimed_by TEXT,
                        claimed_until TIMESTAMP,
                        PRIMARY KEY (flow_name, tenant_id, scheduled_fire_time)
                    )
                    """);
        }
        return dataSource;
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; mirrors
     *  {@code JdbcFlowInstanceStoreClaimTest}'s own helper of the same shape. Every {@code
     *  getConnection()} call opens a fresh physical connection against the SAME named in-memory H2
     *  database ({@code DB_CLOSE_DELAY=-1} keeps it alive between connections), which is what lets
     *  two independent {@code NpdevCronSchedulerService} instances -- each opening its own
     *  connections -- genuinely share one database rather than one connection. */
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
