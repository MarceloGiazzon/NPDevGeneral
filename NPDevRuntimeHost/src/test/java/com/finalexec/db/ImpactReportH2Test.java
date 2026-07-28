package com.finalexec.db;

import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.finalexec.db.schemastate.SafetyClass;
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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 integration coverage for {@link ImpactReport} (schema-engine rebuild, P6.1): each item type's
 * read-only row-count probe against real data + the worst-item-wins verdict, and the never-throws
 * degradation to {@code -1} when a probe cannot run.
 */
class ImpactReportH2Test {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (1, 'alpha', TRUE)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (2, 'a-very-long-name-well-over-ten', NULL)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (3, 'bob', TRUE)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void emptyDiffIsNoChangesWithNoItems() {
        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of()), dataSource);
        assertEquals(ImpactReport.Verdict.NO_CHANGES, report.verdict());
        assertTrue(report.items().isEmpty());
    }

    @Test
    void dropColumnProbesTheNonNullRowsThatWouldDie() {
        SchemaDiffItem drop = SchemaDiffItem.of("DROP_COLUMN:widgets:legacy_flag:BOOLEAN", "widgets", "legacy_flag",
                SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null);
        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(drop)), dataSource);
        assertEquals(ImpactReport.Verdict.DESTRUCTIVE, report.verdict());
        assertEquals(1, report.items().size());
        assertEquals(2L, report.items().get(0).rowsAffected(), "two of three rows have a non-null legacy_flag");
    }

    @Test
    void dropTableProbesTheTotalRowCount() {
        SchemaDiffItem drop = SchemaDiffItem.of("DROP_TABLE:widgets", "widgets", null,
                SafetyClass.DESTRUCTIVE_DROP_TABLE, "widgets", null);
        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(drop)), dataSource);
        assertEquals(ImpactReport.Verdict.DESTRUCTIVE, report.verdict());
        assertEquals(3L, report.items().get(0).rowsAffected());
    }

    @Test
    void narrowVarcharProbesRowsThatWouldBeTruncated() {
        SchemaDiffItem narrow = SchemaDiffItem.of("NARROW_TYPE:widgets:name:VARCHAR(50):VARCHAR(10)", "widgets",
                "name", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "VARCHAR(50)", "VARCHAR(10)");
        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(narrow)), dataSource);
        assertEquals(ImpactReport.Verdict.DESTRUCTIVE, report.verdict());
        assertEquals(1L, report.items().get(0).rowsAffected(), "only the 29-char name exceeds VARCHAR(10)");
    }

    @Test
    void nonCharNarrowingIsManualReviewWorstCaseNonNull() {
        SchemaDiffItem narrow = SchemaDiffItem.of("NARROW_TYPE:widgets:id:BIGINT:INTEGER", "widgets", "id",
                SafetyClass.DESTRUCTIVE_NARROW_TYPE, "BIGINT", "INTEGER");
        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(narrow)), dataSource);
        assertEquals(3L, report.items().get(0).rowsAffected(), "worst case: every non-null id");
        assertTrue(report.items().get(0).probeNote().contains("MANUAL_REVIEW"), report.items().get(0).probeNote());
    }

    @Test
    void newRequiredColumnProbesTotalRowsAndIsNeedsAttention() {
        SchemaDiffItem add = SchemaDiffItem.of("ADD_REQUIRED_COLUMN:widgets:status", "widgets", "status",
                SafetyClass.NEEDS_BACKFILL, null, "VARCHAR(10)");
        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(add)), dataSource);
        assertEquals(ImpactReport.Verdict.NEEDS_ATTENTION, report.verdict());
        assertEquals(3L, report.items().get(0).rowsAffected(), "every existing row needs a value");
    }

    @Test
    void aProbeAgainstAMissingTableDegradesToMinusOneNeverThrows() {
        SchemaDiffItem drop = SchemaDiffItem.of("DROP_TABLE:ghosts", "ghosts", null,
                SafetyClass.DESTRUCTIVE_DROP_TABLE, "ghosts", null);
        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(drop)), dataSource);
        assertEquals(-1L, report.items().get(0).rowsAffected());
        assertTrue(report.items().get(0).probeNote().toLowerCase().contains("probe failed"),
                report.items().get(0).probeNote());
    }

    @Test
    void verdictIsWorstItemWins() {
        SchemaDiffItem add = SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note",
                SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(20)");
        SchemaDiffItem attention = SchemaDiffItem.of("ADD_REQUIRED_COLUMN:widgets:status", "widgets", "status",
                SafetyClass.NEEDS_BACKFILL, null, "VARCHAR(10)");
        SchemaDiffItem destructive = SchemaDiffItem.of("DROP_COLUMN:widgets:legacy_flag:BOOLEAN", "widgets",
                "legacy_flag", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null);
        assertEquals(ImpactReport.Verdict.SAFE, ImpactReport.generate(new SchemaDiff(List.of(add)), dataSource).verdict());
        assertEquals(ImpactReport.Verdict.NEEDS_ATTENTION,
                ImpactReport.generate(new SchemaDiff(List.of(add, attention)), dataSource).verdict());
        assertEquals(ImpactReport.Verdict.DESTRUCTIVE,
                ImpactReport.generate(new SchemaDiff(List.of(add, attention, destructive)), dataSource).verdict());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} (no H2-specific compile dependency). */
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
