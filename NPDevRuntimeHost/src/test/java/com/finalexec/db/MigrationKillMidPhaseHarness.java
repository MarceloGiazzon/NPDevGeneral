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
 * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift" done-when #5): a child-process harness for
 * {@code MigrationKillMidPhaseIT} -- proving "the next boot resumes after a process crash" needs an
 * ACTUAL killed process, not a simulated exception (the plan's own instruction). Two modes:
 *
 * <ul>
 *   <li>{@code crash <dbPath> <crashAfterOrdinalInclusive>} -- runs phases
 *       {@code [0..crashAfterOrdinalInclusive]} of the {@code p75-multi} hook against a file-backed H2
 *       database via the REAL {@link ConversionHookPhaseSplitter}/{@link ConversionHookPhaseRunner}
 *       production entry points, forces a durable {@code CHECKPOINT SYNC} (H2's MVStore otherwise
 *       buffers committed writes for up to a second -- without this, a halt() right after commit could
 *       lose data that was never really about crash-safety, just an unflushed write cache), then calls
 *       {@link Runtime#halt} -- which never returns, runs no shutdown hook and no {@code finally}
 *       block anywhere in the JVM, the closest an in-process test can get to {@code kill -9}.</li>
 *   <li>{@code resume <dbPath>} -- runs the REAL {@link ConversionHookRunner#run}, exactly the normal
 *       boot path, against the same database file. Exit code 0 and a final {@code RESUME_HARNESS: DONE}
 *       line on success.</li>
 *   <li>{@code resume-default <dbPath>} -- STOR-35 (POSTURAL_LIFT_PLAN_2026-09-07.md package P7): the
 *       same resume, but with {@code mixedDdlVerify} set by NOBODY -- the child asserts the property is
 *       null and reaches split mode purely through the new DEFAULT, proving the crash-resume claim
 *       holds for the default, not just the opt-in.</li>
 *   <li>{@code resume-warn <dbPath>} -- STOR-35 (P7, rollback rehearsal): the same resume with
 *       {@code mixedDdlVerify=warn} set EXPLICITLY -- the escape-hatch path, run raw with a WARNING,
 *       no journal rows, byte-for-byte the pre-flip behavior; used by the rollback-rehearsal log pair.</li>
 * </ul>
 *
 * <p>The {@code crash} mode always pins {@code mixedDdlVerify=split} (it drives the splitter directly
 * and must classify the fixture SQL), and the {@code resume} mode pins it too -- only
 * {@code resume-default} runs property-less, by construction, while {@code resume-warn} pins the
 * escape hatch.</p>
 *
 * <p>{@link #CONVERT_SQL} is a byte-identical copy of {@code
 * src/test/resources/db/conversion-hooks/p75-multi/convert.sql}, duplicated here deliberately: this
 * harness exercises the splitter/runner directly (crash mode must stop after an EXACT phase, which
 * {@code ConversionHookRunner.run}'s own hook loop has no seam for), so it must not depend on how
 * {@code ConversionHookRunner} locates hook resources on the classpath.
 */
final class MigrationKillMidPhaseHarness {

    private static final String TABLE = "p75_multi";
    private static final String HOOK_ID = "p75-multi";
    private static final String CONVERT_SQL =
            "ALTER TABLE p75_multi ADD COLUMN status VARCHAR(20);\n"
                    + "UPDATE p75_multi SET status = 'unknown' WHERE status IS NULL;\n"
                    + "ALTER TABLE p75_multi ALTER COLUMN status SET NOT NULL;\n";

    private MigrationKillMidPhaseHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: <crash|resume|resume-default|resume-warn> <dbPath> [crashAfterOrdinalInclusive]");
        }
        String mode = args[0];
        String dbPath = args[1];
        String url = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE";
        DataSource dataSource = new SimpleUrlDataSource(url);
        SqlDialects.setActive(H2Dialect.INSTANCE);
        // STOR-35 (P7): only the resume-default mode runs WITHOUT the property -- it must reach split
        // purely through the new default, and it asserts that in-child. resume-warn pins the escape
        // hatch. crash and resume pin split (crash drives the splitter directly).
        if ("resume-default".equals(mode)) {
            if (System.getProperty("npdev.schema.conversionHooks.mixedDdlVerify") != null) {
                throw new IllegalStateException("RESUME_HARNESS: mixedDdlVerify property leaked into the "
                        + "child -- resume-default must reach split mode purely through the default.");
            }
        } else {
            System.setProperty("npdev.schema.conversionHooks.mixedDdlVerify",
                    "resume-warn".equals(mode) ? "warn" : "split");
        }

        switch (mode) {
            case "crash" -> runCrashMode(dataSource, Integer.parseInt(args[2]));
            case "resume", "resume-default", "resume-warn" -> runResumeMode(dataSource);
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void runCrashMode(DataSource dataSource, int crashAfterOrdinalInclusive) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO " + TABLE + " (id) VALUES (1), (2), (3)");
        }
        ConversionHookPhaseSplitter.SplitResult split = ConversionHookPhaseSplitter.split(CONVERT_SQL, H2Dialect.INSTANCE);
        if (!split.isSplittable()) {
            throw new IllegalStateException("fixture SQL must split cleanly: " + split.blocked());
        }
        List<ConversionHookPhaseSplitter.Phase> prefix = split.phases().subList(0, crashAfterOrdinalInclusive + 1);
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor();
        String migrationId = MigrationPhaseJournal.migrationId(
                SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource), manifest.schemaFingerprint());

        ConversionHookPhaseRunner.run(dataSource, migrationId, HOOK_ID, prefix);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CHECKPOINT SYNC"); // force the durable flush a real halt() must survive
        }
        System.out.println("CRASH_HARNESS: completed phases 0.." + crashAfterOrdinalInclusive + "; halting now.");
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

    /** Mirrors {@code ConversionHookRunnerH2Test#manifestFor}'s shape for the {@code p75_multi} table
     *  -- kept in sync by hand (this harness has no access to that test class's private helper). */
    private static SchemaLifecycleExecutor.SchemaManifest manifestFor() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:p75-multi-kill-test",
                List.of(), List.of(TABLE),
                Map.of(TABLE, List.of("id", "status")),
                Map.of(TABLE, List.of()),
                Map.of(TABLE, Map.of("id", "BIGINT", "status", "VARCHAR(20)")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(TABLE, List.of("status")), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- no H2-specific compile dependency,
     *  mirroring the pattern every other test {@code UrlDataSource} in this package already uses. */
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
