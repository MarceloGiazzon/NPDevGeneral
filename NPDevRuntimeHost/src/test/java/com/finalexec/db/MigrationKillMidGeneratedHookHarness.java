package com.finalexec.db;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * STOR-22: a child-process harness proving a GENERATED conversion hook killed on the DEFAULT
 * (un-split, single-transaction {@code executeAndVerify}) path is re-selected and resumed on the next
 * boot -- the same rigor {@link MigrationKillMidPhaseHarness} already gave the opt-in SPLIT path, for
 * the path every {@code conversions[]}-generated hook actually takes today. Two modes:
 *
 * <ul>
 *   <li>{@code crash <dbPath>} -- issues only the raw {@code ADD COLUMN} statement directly against a
 *       file-backed H2 database (simulating the first statement of {@code executeAndVerify}'s loop
 *       having auto-committed on H2 -- {@code ConversionHookRunner} is deliberately never invoked here,
 *       the same reasoning {@link MigrationKillMidPhaseHarness}'s own javadoc gives for driving the
 *       lower-level entry point directly: crash mode must stop at an EXACT statement, which the
 *       production hook loop has no seam for), forces a durable {@code CHECKPOINT SYNC}, then calls
 *       {@link Runtime#halt} -- the closest an in-process test can get to {@code kill -9}.</li>
 *   <li>{@code resume <dbPath>} -- runs the REAL {@link ConversionHookRunner#run} 3-arg overload
 *       (default {@code warn} mode, no split -- exactly what a generated hook uses in production)
 *       against the same database file. Exit code 0 and a final {@code RESUME_HARNESS: DONE} line on
 *       success.</li>
 * </ul>
 *
 * <p>No {@code -Dnpdev.schema.conversionHooks.mixedDdlVerify} system property is set here -- the whole
 * point is proving resumption under the platform's own default, not the SPLIT opt-in
 * {@link MigrationKillMidPhaseHarness} already covers.
 */
final class MigrationKillMidGeneratedHookHarness {

    private static final String TABLE = "stor22_conv";

    private MigrationKillMidGeneratedHookHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: <crash|resume> <dbPath>");
        }
        String mode = args[0];
        String dbPath = args[1];
        String url = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE";
        DataSource dataSource = new SimpleUrlDataSource(url);
        SqlDialects.setActive(H2Dialect.INSTANCE);

        switch (mode) {
            case "crash" -> runCrashMode(dataSource);
            case "resume" -> runResumeMode(dataSource);
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void runCrashMode(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO " + TABLE + " (id) VALUES (1), (2), (3)");
            // The hook's first statement only -- H2 has no transactional DDL (boundary B11), so this
            // commits immediately, exactly like the first iteration of executeAndVerify's statement
            // loop would, before the UPDATE/SET NOT NULL statements after it ever run.
            statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN status VARCHAR(20)");
        }

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CHECKPOINT SYNC"); // force the durable flush a real halt() must survive
        }
        System.out.println("CRASH_HARNESS: ADD COLUMN committed; halting before UPDATE/SET NOT NULL.");
        System.out.flush();
        Runtime.getRuntime().halt(137); // never returns -- no shutdown hook, no finally block anywhere runs
    }

    private static void runResumeMode(DataSource dataSource) throws SQLException {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor();
        List<String[]> history = new ArrayList<>();
        ConversionHookRunner.HistoryWriter historyWriter =
                (label, outcome, details) -> history.add(new String[] {label, outcome, String.valueOf(details)});

        ConversionHookRunner.run(dataSource, manifest, historyWriter);

        for (String[] row : history) {
            System.out.println("RESUME_HARNESS: " + row[0] + " " + row[1] + " " + row[2]);
        }
        System.out.println("RESUME_HARNESS: DONE");
    }

    /** Mirrors {@link MigrationKillMidPhaseHarness#manifestFor()}'s shape for the {@code stor22_conv}
     *  table -- required-not-null column, no literal default, so the diff classifies the post-crash
     *  stuck-nullable state as {@code TIGHTEN_NOT_NULL:...} -> {@code NEEDS_HOOK}, matching the real
     *  generated-hook case exactly. */
    private static SchemaLifecycleExecutor.SchemaManifest manifestFor() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:stor22-copy-kill-test",
                List.of(), List.of(TABLE),
                Map.of(TABLE, List.of("id", "status")),
                Map.of(TABLE, List.of()),
                Map.of(TABLE, Map.of("id", "BIGINT", "status", "VARCHAR(20)")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(TABLE, List.of("status")), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- mirrors
     *  {@link MigrationKillMidPhaseHarness}'s own copy of this pattern. */
    private static final class SimpleUrlDataSource implements DataSource {
        private final String url;

        private SimpleUrlDataSource(String url) {
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
