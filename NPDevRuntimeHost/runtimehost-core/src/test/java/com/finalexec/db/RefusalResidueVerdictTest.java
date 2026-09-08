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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package P4): the verdict computation over a
 * real H2 journal -- all-idempotent committed steps are {@code RETRY_SAFE}, one non-idempotent step
 * makes the whole boot {@code INSPECT_FIRST} naming it, and an unreadable/absent journal degrades to
 * an empty residue that leaves a refusal's message byte-identical (the negative half of the done-when:
 * a refusal must never fail -- or change -- because its residue could not be read).
 */
class RefusalResidueVerdictTest {

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
    void allIdempotentCommittedStepsAreRetrySafe() {
        BootResidueJournal.started(dataSource, "boot-retry-safe", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.committed(dataSource, "boot-retry-safe", BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);
        BootResidueJournal.started(dataSource, "boot-retry-safe", null,
                BootResidueJournal.LifecycleStep.IN_PLACE_RENAME_WIDEN, null);
        BootResidueJournal.committed(dataSource, "boot-retry-safe", BootResidueJournal.LifecycleStep.IN_PLACE_RENAME_WIDEN);

        RefusalResidue.Residue residue = RefusalResidue.forBoot(dataSource, "boot-retry-safe");

        assertEquals(RefusalResidue.Verdict.RETRY_SAFE, residue.verdict(), residue.toString());
        assertEquals(2, residue.steps().size());
        assertFalse(residue.isEmpty());
    }

    @Test
    void oneNonIdempotentCommittedStepIsInspectFirstAndNamesIt() {
        BootResidueJournal.started(dataSource, "boot-inspect", null,
                BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
        BootResidueJournal.committed(dataSource, "boot-inspect", BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);
        BootResidueJournal.started(dataSource, "boot-inspect", null,
                BootResidueJournal.LifecycleStep.CONVERSION_HOOKS, "hook '0004-split-name'");
        BootResidueJournal.committed(dataSource, "boot-inspect", BootResidueJournal.LifecycleStep.CONVERSION_HOOKS);

        RefusalResidue.Residue residue = RefusalResidue.forBoot(dataSource, "boot-inspect");

        assertEquals(RefusalResidue.Verdict.INSPECT_FIRST, residue.verdict());
        String rendered = SchemaRefusal.render(residue);
        assertTrue(rendered.contains("B7:refusal_residue:"), rendered);
        assertTrue(rendered.contains("CONVERSION_HOOKS"), rendered);
        assertTrue(rendered.contains("NOT idempotent"), rendered);
        assertTrue(rendered.contains("hook '0004-split-name'"), rendered);
        assertTrue(rendered.contains("Verdict: INSPECT_FIRST"), rendered);
    }

    @Test
    void anEmptyJournalLeavesTheRefusalMessageByteIdentical() {
        String original = "some refusal message that must never change";

        assertEquals(original, SchemaRefusal.withResidue(original, dataSource, "boot-that-never-journaled"),
                "with no committed steps there is nothing to append and the message stands alone");
    }

    @Test
    void aDroppedJournalTableDegradesToTheBareMessageInsteadOfFailingTheRefusal() throws SQLException {
        // The boot committed steps, then the journal table went away mid-flight -- the residue read
        // must fail CLOSED (bare message + one log line), never turn the refusal into a different
        // exception or a different message.
        BootResidueJournal.started(dataSource, "boot-lost-journal", null,
                BootResidueJournal.LifecycleStep.CONVERSION_HOOKS, null);
        BootResidueJournal.committed(dataSource, "boot-lost-journal",
                BootResidueJournal.LifecycleStep.CONVERSION_HOOKS);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE npdev_boot_residue_journal");
        }

        String original = "B5:schema_ahead_detected:THE ORIGINAL REFUSAL";
        assertEquals(original, SchemaRefusal.withResidue(original, dataSource, "boot-lost-journal"),
                "a refusal must never fail because its residue could not be read");
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