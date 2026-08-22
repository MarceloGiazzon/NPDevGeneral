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
 * LNCH-12 DoD: "verified by shrinking the cron to seconds in a gate test and observing" the
 * outcome -- a schedule.cron of {@code "* * * * * *"} (every second) proves the full path: model
 * declares a schedule -> the scheduler registers a CronTrigger -> KernelRunner.execute runs the
 * flow under a system principal -> the outcome tracker (ControlPanel's data source) records it.
 */
final class NpdevCronSchedulerServiceTest {

    @Test
    void scheduledFlowRunsRepeatedlyUnderSystemPrincipalAndRecordsOutcome() throws Exception {
        CompiledModel compiledModel = new CompiledModel(
                "demo",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CompiledFlow(
                        "CloseStaleOrders",
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

        CapabilityRegistry registry = new CapabilityRegistry();
        KernelRunner runner = new KernelRunner(
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

        ScheduleOutcomeTracker tracker = new ScheduleOutcomeTracker();
        DataSource dataSource = newClaimTableDataSource();
        NpdevCronSchedulerService scheduler = new NpdevCronSchedulerService(compiledModel, runner, tracker, 2, dataSource);
        scheduler.start();
        try {
            long deadline = System.currentTimeMillis() + 5000;
            ScheduleOutcome outcome = null;
            while (System.currentTimeMillis() < deadline) {
                outcome = tracker.all().stream()
                        .filter(o -> "CloseStaleOrders".equals(o.flowName()) && "tenant-a".equals(o.tenantId()))
                        .findFirst().orElse(null);
                if (outcome != null && outcome.runCount() >= 2) {
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(outcome != null, "expected an outcome to be recorded for the scheduled flow");
            assertEquals(ScheduleOutcome.STATUS_SUCCESS, outcome.status());
            assertTrue(outcome.runCount() >= 2, "expected at least 2 runs within 5s of a 1-second cron, got " + outcome.runCount());
            assertEquals("* * * * * *", outcome.cron());
        } finally {
            scheduler.stop();
        }
    }

    /**
     * R2.7: {@link NpdevCronSchedulerService} now claims via {@link CronFireClaimStore} before every
     * run, which needs a real {@code npdev_cron_fire_claim} table -- a fresh in-memory H2 database,
     * hand-built in the exact shape {@link com.npdev.kernel.dbschema.NpdevCronFireClaimTable} declares,
     * mirroring {@code JdbcFlowInstanceStoreClaimTest}'s own technique for RUN-2's claim table.
     */
    private static DataSource newClaimTableDataSource() throws SQLException {
        String url = "jdbc:h2:mem:" + NpdevCronSchedulerServiceTest.class.getSimpleName() + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1";
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
