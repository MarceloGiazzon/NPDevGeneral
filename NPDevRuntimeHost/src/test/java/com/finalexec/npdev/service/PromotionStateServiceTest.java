package com.finalexec.npdev.service;

import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the S0-S8 gate rules: no stage-skipping, evidence required from S5 on, ADMIN role required
 * for S7/S8 — and that every attempt, accepted or rejected, is appended to history rather than
 * silently dropped or overwriting a single mutable flag.
 */
class PromotionStateServiceTest {

    private DataSource dataSource;
    private PromotionStateService service;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE npdev_promotion_state ("
                    + "event_id VARCHAR(64) NOT NULL, ts_ms BIGINT NOT NULL, stage VARCHAR(64) NOT NULL, "
                    + "actor_id VARCHAR(120), roles VARCHAR(255), evidence VARCHAR(2000), "
                    + "outcome VARCHAR(32) NOT NULL, reason_code VARCHAR(500), PRIMARY KEY (event_id))");
        }
        service = new PromotionStateService(new FixedObjectProvider(dataSource));
    }

    private static ExecutionContext admin() {
        return ExecutionContext.of("dev", "carol").withRoles(Set.of("ADMIN"));
    }

    private static ExecutionContext user() {
        return ExecutionContext.of("dev", "dave").withRoles(Set.of("USER"));
    }

    @Test
    void startsAtS0WithEmptyHistory() {
        Map<String, Object> state = service.currentState();
        assertEquals("S0_IDEA", state.get("currentStage"));
        assertTrue(((List<?>) state.get("history")).isEmpty());
    }

    @Test
    void rejectsSkippingAStage() {
        PromotionStateService.PromotionRejectedException exception = assertThrows(
                PromotionStateService.PromotionRejectedException.class,
                () -> service.advance(PromotionStateService.Stage.S8_RELEASED, "trust me", admin()));
        assertTrue(exception.getMessage().contains("stage_skip"));
    }

    @Test
    void requiresEvidenceFromS5Onward() throws Exception {
        advanceThroughEarlyStages();
        PromotionStateService.PromotionRejectedException exception = assertThrows(
                PromotionStateService.PromotionRejectedException.class,
                () -> service.advance(PromotionStateService.Stage.S5_TESTED, "", admin()));
        assertTrue(exception.getMessage().contains("missing_evidence"));

        service.advance(PromotionStateService.Stage.S5_TESTED, "tests green", admin());
        assertEquals("S5_TESTED", service.currentState().get("currentStage"));
    }

    @Test
    void requiresAdminRoleForReleaseApprovalAndRecordsTheRejectedAttempt() throws Exception {
        advanceThroughEarlyStages();
        service.advance(PromotionStateService.Stage.S5_TESTED, "tests green", admin());
        service.advance(PromotionStateService.Stage.S6_EVIDENCE_BACKED, "evidence reviewed", admin());

        PromotionStateService.PromotionRejectedException exception = assertThrows(
                PromotionStateService.PromotionRejectedException.class,
                () -> service.advance(PromotionStateService.Stage.S7_RELEASE_APPROVED, "looks fine", user()));
        assertTrue(exception.getMessage().contains("missing_role"));
        // Still S6 — a rejected attempt never moves the current stage.
        assertEquals("S6_EVIDENCE_BACKED", service.currentState().get("currentStage"));

        service.advance(PromotionStateService.Stage.S7_RELEASE_APPROVED, "approved by carol", admin());
        assertEquals("S7_RELEASE_APPROVED", service.currentState().get("currentStage"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) service.currentState().get("history");
        long rejectedCount = history.stream().filter(event -> "REJECTED".equals(event.get("outcome"))).count();
        assertEquals(1, rejectedCount, "the non-admin's rejected attempt must still be in history");
    }

    private void advanceThroughEarlyStages() {
        service.advance(PromotionStateService.Stage.S1_DECLARED, null, admin());
        service.advance(PromotionStateService.Stage.S2_GENERATED, null, admin());
        service.advance(PromotionStateService.Stage.S3_CUSTOMIZED, null, admin());
        service.advance(PromotionStateService.Stage.S4_RUNNABLE, null, admin());
    }

    private static final class FixedObjectProvider implements ObjectProvider<DataSource> {
        private final DataSource dataSource;

        private FixedObjectProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public DataSource getObject(Object... args) {
            return dataSource;
        }

        @Override
        public DataSource getIfAvailable() {
            return dataSource;
        }

        @Override
        public DataSource getIfAvailable(Supplier<DataSource> defaultSupplier) {
            return dataSource;
        }

        @Override
        public DataSource getIfUnique() {
            return dataSource;
        }

        @Override
        public DataSource getIfUnique(Supplier<DataSource> defaultSupplier) {
            return dataSource;
        }

        @Override
        public DataSource getObject() {
            return dataSource;
        }
    }

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
