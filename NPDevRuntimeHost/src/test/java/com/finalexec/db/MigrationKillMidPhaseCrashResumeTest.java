package com.finalexec.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift" done-when #5): "the acceptance test that matters"
 * per the plan's own words -- kills a REAL child JVM process mid-migration (not a simulated
 * exception) and proves the NEXT boot resumes and completes with no human step and no data loss.
 *
 * <p>Two child processes against one file-backed H2 database ({@code AUTO_SERVER=FALSE}, so only one
 * process holds it at a time -- this test's own JDBC verification connections open and close between
 * the two child runs for exactly that reason):
 *
 * <ol>
 *   <li>{@code crash} mode runs phase 0 only (the {@code ADD COLUMN}) of the {@code p75-multi} hook's
 *       three phases, forces a durable {@code CHECKPOINT SYNC}, then calls {@link Runtime#halt} --
 *       skipping every JVM shutdown hook and {@code finally} block, the closest an in-process test can
 *       get to {@code kill -9}.</li>
 *   <li>{@code resume} mode runs the REAL {@code ConversionHookRunner.run} boot path against the same
 *       database file -- exactly what a real second boot does.</li>
 * </ol>
 */
class MigrationKillMidPhaseCrashResumeTest {

    @Test
    void aKilledMigrationResumesOnTheNextBootWithNoDataLoss(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.resolve("kill-mid-phase-db").toAbsolutePath().toString();

        HarnessResult crashResult = runHarness(tempDir, "crash-run", "crash", dbPath, "0");
        assertEquals(137, crashResult.exitCode(),
                "the harness must die via Runtime.halt(137), not exit normally: " + crashResult.output());
        assertTrue(crashResult.output().contains("CRASH_HARNESS: completed phases 0..0"), crashResult.output());

        // Intermediate state: phase 0 (ADD COLUMN) committed; phase 1 (the DML backfill) never ran.
        // This is the exact half-applied window STOR-20's hard refusal used to make impossible to
        // reach at all on an implicit-commit engine -- proving it is real, not assumed, before
        // proving it resumes.
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE";
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            assertEquals(3L, singleLongQuery(connection, "SELECT COUNT(*) FROM p75_multi WHERE status IS NULL"),
                    "the DML backfill must NOT have run yet -- every row's status must still be NULL");
        }

        HarnessResult resumeResult = runHarness(tempDir, "resume-run", "resume", dbPath);
        assertEquals(0, resumeResult.exitCode(), "the resumed boot must complete cleanly: " + resumeResult.output());
        assertTrue(resumeResult.output().contains("RESUME_HARNESS: DONE"), resumeResult.output());
        assertTrue(resumeResult.output().contains("HOOK_PHASES_APPLIED") && resumeResult.output().contains("ran=2")
                        && resumeResult.output().contains("resumedSkipped=1"),
                "must run exactly the 2 phases not yet completed (the DML backfill + the SET NOT NULL) and "
                        + "skip the 1 already-completed phase (the ADD COLUMN), never re-attempt it: "
                        + resumeResult.output());

        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            assertEquals(0L, singleLongQuery(connection, "SELECT COUNT(*) FROM p75_multi WHERE status IS NULL"),
                    "every row must now be backfilled -- no data loss, no row left behind");
            assertEquals(3L, singleLongQuery(connection, "SELECT COUNT(*) FROM p75_multi WHERE status = 'unknown'"));
            assertEquals(3L, singleLongQuery(connection, "SELECT COUNT(*) FROM p75_multi"),
                    "no row was lost or duplicated across the crash/resume boundary");
        }
    }

    private record HarnessResult(int exitCode, String output) {
    }

    private HarnessResult runHarness(Path tempDir, String logFileName, String... harnessArgs) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        // A generated FinalApp's classpath (50+ staged platform jars plus Spring Boot's own
        // dependencies) is long enough to exceed Windows' CreateProcess command-line length limit when
        // passed directly as `-cp <classpath>` -- ProcessBuilder#start() throws a real IOException, not
        // a test bug. A JDK argument file (`java @file`, JDK 9+) is immune to that limit: the OS only
        // ever sees `java @<shortpath>`, never the long classpath line. One token per line so no
        // quoting/escaping is needed even though Windows paths are full of backslashes (an argfile's
        // quoted-token escaping treats backslash specially, which a raw path is not written for).
        Path argsFile = tempDir.resolve(logFileName + ".argfile");
        StringBuilder argsContent = new StringBuilder();
        argsContent.append("-cp\n").append(classpath).append('\n');
        argsContent.append("com.finalexec.db.MigrationKillMidPhaseHarness\n");
        for (String arg : harnessArgs) {
            argsContent.append(arg).append('\n');
        }
        Files.writeString(argsFile, argsContent.toString());

        List<String> command = List.of(javaBin, "@" + argsFile.toAbsolutePath());

        File logFile = tempDir.resolve(logFileName + ".log").toFile();
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start();
        } catch (java.io.IOException startFailure) {
            throw new java.io.IOException("failed starting harness child process (javaBin=" + javaBin
                    + ", classpath length=" + classpath.length() + " chars, argsFile=" + argsFile + "): "
                    + startFailure.getMessage(), startFailure);
        }
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("harness process (" + String.join(" ", harnessArgs) + ") did not exit within 60s");
        }
        int exitCode = process.exitValue();
        String output = Files.readString(logFile.toPath());
        return new HarnessResult(exitCode, output);
    }

    private static long singleLongQuery(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : -1L;
        }
    }
}
