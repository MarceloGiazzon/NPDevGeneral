package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package P4): direct unit tests for
 * {@link BootResidueJournal} against real H2 -- the write-before-execute / confirm-after contract,
 * the "started but not committed never counts as committed" discipline (the journal row for a pass
 * that threw mid-execution is never read as committed work), and the two readback queries the
 * surfaces use (latest refused boot, step in flight).
 */
class BootResidueJournalTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void stepsAreRecordedInExecutionOrder() {
        BootResidueJournal.started(dataSource, "boot-1", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.committed(dataSource, "boot-1", BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);
        BootResidueJournal.started(dataSource, "boot-1", null,
                BootResidueJournal.LifecycleStep.IN_PLACE_RENAME_WIDEN, "renamed orders.id");
        BootResidueJournal.committed(dataSource, "boot-1", BootResidueJournal.LifecycleStep.IN_PLACE_RENAME_WIDEN);
        BootResidueJournal.started(dataSource, "boot-1", null,
                BootResidueJournal.LifecycleStep.CONVERSION_HOOKS, null);
        BootResidueJournal.committed(dataSource, "boot-1", BootResidueJournal.LifecycleStep.CONVERSION_HOOKS);

        List<BootResidueJournal.CommittedStep> steps =
                BootResidueJournal.committedSteps(dataSource, "boot-1");

        assertEquals(3, steps.size(), steps.toString());
        assertEquals("MIGRATION_CLAIM", steps.get(0).stepName());
        assertEquals("IN_PLACE_RENAME_WIDEN", steps.get(1).stepName());
        assertEquals("CONVERSION_HOOKS", steps.get(2).stepName());
        assertTrue(steps.get(0).idempotent());
        assertTrue(steps.get(1).idempotent());
        assertFalse(steps.get(2).idempotent(),
                "CONVERSION_HOOKS is declared non-idempotent -- the operator-authoring contract is unproven");
        assertEquals("renamed orders.id", steps.get(1).detail(), "the detail rides along for readback context");
    }

    @Test
    void aStepStartedButNotCommittedNeverCountsAsCommitted() {
        // The boot died inside IN_PLACE_RENAME_WIDEN -- its row has outcome NULL and must not be
        // read back as committed work a retry would re-execute.
        BootResidueJournal.started(dataSource, "boot-1", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.committed(dataSource, "boot-1", BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);
        BootResidueJournal.started(dataSource, "boot-1", null,
                BootResidueJournal.LifecycleStep.IN_PLACE_RENAME_WIDEN, null);

        List<BootResidueJournal.CommittedStep> steps =
                BootResidueJournal.committedSteps(dataSource, "boot-1");

        assertEquals(1, steps.size(), "only MIGRATION_CLAIM completed -- IN_PLACE started but did not commit: "
                + steps);
        assertEquals("MIGRATION_CLAIM", steps.get(0).stepName());
    }

    @Test
    void anUnrecordedBootHasNoCommittedSteps() {
        assertTrue(BootResidueJournal.committedSteps(dataSource, "never-started").isEmpty());
    }

    @Test
    void aBootWithAnUncommittedStepIsTheLatestRefusedBoot() {
        BootResidueJournal.started(dataSource, "boot-refused", null,
                BootResidueJournal.LifecycleStep.CONVERSION_HOOKS, null);

        Optional<String> latest = BootResidueJournal.latestRefusedBootId(dataSource);

        assertTrue(latest.isPresent(), "a started-not-committed step marks a boot as refused");
        assertEquals("boot-refused", latest.get());
        assertEquals(Optional.of(BootResidueJournal.LifecycleStep.CONVERSION_HOOKS),
                BootResidueJournal.stepInFlight(dataSource, "boot-refused"),
                "the refused boot's in-flight step is the one that never committed");
    }

    @Test
    void aFullyCommittedBootIsNotAReportedRefusal() {
        BootResidueJournal.started(dataSource, "boot-ok", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.committed(dataSource, "boot-ok", BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);

        assertTrue(BootResidueJournal.latestRefusedBootId(dataSource).isEmpty(),
                "a boot whose every started step committed is not a refusal");
    }

    @Test
    void theRefusedBootSurvivesALaterSuccessfulBoot() {
        BootResidueJournal.started(dataSource, "boot-refused", null,
                BootResidueJournal.LifecycleStep.CONVERSION_HOOKS, null);
        BootResidueJournal.started(dataSource, "boot-ok", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.committed(dataSource, "boot-ok", BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);

        assertTrue(BootResidueJournal.latestRefusedBootId(dataSource).isPresent(),
                "the refused boot's rows are never deleted -- a later successful boot must not erase it");
    }

    @Test
    void aReplayedBootOverwritesItsOwnRowsRatherThanAccumulating() {
        BootResidueJournal.started(dataSource, "boot-1", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.started(dataSource, "boot-1", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.committed(dataSource, "boot-1", BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);

        assertEquals(1, BootResidueJournal.committedSteps(dataSource, "boot-1").size(),
                "a replayed step with the same (boot_id, step_ordinal) overwrites, never accumulates");
    }

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