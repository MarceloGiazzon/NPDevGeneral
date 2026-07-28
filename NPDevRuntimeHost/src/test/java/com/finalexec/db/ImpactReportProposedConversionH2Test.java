package com.finalexec.db;

import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffItem;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER-P8.1/P8.3: the proposed-conversion draft actually reaches both renderers through a real
 * {@link ImpactReport#generate}, not just {@link ProposedConversionSqlTest}'s unit-level checks.
 */
class ImpactReportProposedConversionH2Test {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void jsonAndTextBothCarryTheDraftForAConvertibleNarrowing() {
        SchemaDiffItem narrow = SchemaDiffItem.of("NARROW_TYPE:widgets:name:VARCHAR(50):VARCHAR(10)", "widgets",
                "name", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "VARCHAR(50)", "VARCHAR(10)");

        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(narrow)), dataSource);

        String json = ImpactReportJson.render(report, "2026-07-24T00:00:00Z", "sha256:old", "sha256:new", "tok");
        assertTrue(json.contains("\"proposedConversionSql\": \"ALTER TABLE widgets ADD COLUMN name__new VARCHAR(10);"),
                json);
        assertFalse(json.contains("\"proposedConversionSql\": null"), json);

        String text = ImpactReportText.render(report, "sha256:old", "sha256:new", "tok");
        assertTrue(text.contains("proposed conversions"), text);
        assertTrue(text.contains("ALTER TABLE widgets ADD COLUMN name__new VARCHAR(10);"), text);
        assertTrue(text.contains("verifySql: SELECT COUNT(*) FROM widgets WHERE name IS NOT NULL AND name__new IS NULL"), text);
    }

    @Test
    void incomparableNarrowingGetsTheWriteACustomHookNoteNotADraft() {
        SchemaDiffItem narrow = SchemaDiffItem.of("NARROW_TYPE:widgets:id:BIGINT:VARCHAR(20)", "widgets",
                "id", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "BIGINT", "VARCHAR(20)");

        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(narrow)), dataSource);

        String json = ImpactReportJson.render(report, "2026-07-24T00:00:00Z", "sha256:old", "sha256:new", "tok");
        assertTrue(json.contains("\"proposedConversionSql\": null"), json);

        String text = ImpactReportText.render(report, "sha256:old", "sha256:new", "tok");
        assertTrue(text.contains("no safe automatic conversion -- write a custom hook."), text);
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
