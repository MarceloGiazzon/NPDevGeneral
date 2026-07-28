package com.finalexec.db;

import com.finalexec.db.schemastate.Resolution;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER-P7.4: an item a conversion hook claims (read-only preview -- no hook actually runs here) renders
 * as {@code HOOK_CLAIMED} / {@code "HOOK: <id>"} instead of {@code !!}, and does not count toward
 * {@code NEEDS_ATTENTION}/{@code DESTRUCTIVE}. Reuses the real classpath fixture
 * {@code src/test/resources/db/conversion-hooks/p76-drop-legacy} (claims {@code
 * DROP_COLUMN:p76_widgets:legacy_flag:BOOLEAN}) -- {@code ImpactReport.generate} never executes it,
 * only reads its claim.
 */
class ImpactReportHookClaimH2Test {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE p76_widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO p76_widgets (id, legacy_flag) VALUES (1, TRUE)");
            statement.execute("CREATE TABLE unclaimed_widgets (id BIGINT PRIMARY KEY, mystery BOOLEAN)");
            statement.execute("INSERT INTO unclaimed_widgets (id, mystery) VALUES (1, TRUE)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void aClaimedItemIsMarkedHookClaimedAndDoesNotCountAsDestructive() {
        SchemaDiffItem claimed = SchemaDiffItem.of("DROP_COLUMN:p76_widgets:legacy_flag:BOOLEAN", "p76_widgets",
                "legacy_flag", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null);

        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(claimed)), dataSource);

        assertEquals(ImpactReport.Verdict.SAFE, report.verdict(),
                "the ONLY destructive item is fully claimed by a hook -- verdict must not be DESTRUCTIVE");
        assertEquals(1, report.items().size());
        ImpactReport.Item item = report.items().get(0);
        assertEquals(Resolution.HOOK_CLAIMED, item.diffItem().resolution());
        assertTrue(item.probeNote().contains("HOOK: p76-drop-legacy"), item.probeNote());
        assertEquals(1L, item.rowsAffected(), "the probe still runs -- an operator still sees the blast radius");

        String text = ImpactReportText.render(report, "sha256:old", "sha256:new", null);
        assertFalse(text.contains("!!"), "a claimed item must not show the raw destructive marker: " + text);
        assertTrue(text.contains("HOOK"), text);
        assertTrue(text.contains("HOOK: p76-drop-legacy"), text);

        String json = ImpactReportJson.render(report, "2026-07-24T00:00:00Z", "sha256:old", "sha256:new", null);
        assertTrue(json.contains("\"resolution\": \"HOOK_CLAIMED\""), json);
    }

    @Test
    void anUnclaimedDestructiveItemStillShowsTheRawMarkerAndDrivesTheVerdict() {
        SchemaDiffItem unclaimed = SchemaDiffItem.of("DROP_COLUMN:unclaimed_widgets:mystery:BOOLEAN",
                "unclaimed_widgets", "mystery", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null);

        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(unclaimed)), dataSource);

        assertEquals(ImpactReport.Verdict.DESTRUCTIVE, report.verdict());
        assertEquals(Resolution.UNRESOLVED, report.items().get(0).diffItem().resolution());

        String text = ImpactReportText.render(report, "sha256:old", "sha256:new", "tok123");
        assertTrue(text.contains("!!"), text);
        assertFalse(text.contains("HOOK"), text);
        assertTrue(text.contains("acknowledgment token"), text);
    }

    @Test
    void bothClaimedAndUnclaimedInTheSameReport_verdictReflectsOnlyTheUnclaimedOne() {
        SchemaDiffItem claimed = SchemaDiffItem.of("DROP_COLUMN:p76_widgets:legacy_flag:BOOLEAN", "p76_widgets",
                "legacy_flag", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null);
        SchemaDiffItem unclaimed = SchemaDiffItem.of("DROP_COLUMN:unclaimed_widgets:mystery:BOOLEAN",
                "unclaimed_widgets", "mystery", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null);

        ImpactReport report = ImpactReport.generate(new SchemaDiff(List.of(claimed, unclaimed)), dataSource);

        assertEquals(ImpactReport.Verdict.DESTRUCTIVE, report.verdict(), "the unclaimed item alone must still drive DESTRUCTIVE");
        long claimedCount = report.items().stream().filter(i -> i.diffItem().resolution() == Resolution.HOOK_CLAIMED).count();
        long unresolvedCount = report.items().stream().filter(i -> i.diffItem().resolution() == Resolution.UNRESOLVED).count();
        assertEquals(1, claimedCount);
        assertEquals(1, unresolvedCount);
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
