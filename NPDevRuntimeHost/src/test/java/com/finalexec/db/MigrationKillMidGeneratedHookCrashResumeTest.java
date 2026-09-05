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
 * STOR-22: "the acceptance test that matters" for the default (un-split) path, mirroring what
 * {@link MigrationKillMidPhaseCrashResumeTest} already proved for the opt-in SPLIT path -- kills a
 * REAL child JVM process right after a generated hook's {@code ADD COLUMN} commits (H2 has no
 * transactional DDL, boundary B11) and proves the NEXT boot re-selects the hook and finishes it, with
 * no human step and no data loss, under the platform's DEFAULT {@code warn}-mode configuration (no
 * opt-in property set anywhere in this test).
 *
 * <p>Before the fix, {@code ConversionHookRunner}'s selection loop only re-selects a hook via an exact
 * claim match or {@code MigrationPhaseJournal} activity -- and the journal is only ever written by
 * SPLIT mode. This crash window leaves the hook's target column reclassified from
 * {@code ADD_REQUIRED_COLUMN:...} to {@code TIGHTEN_NOT_NULL:...} (a different item key), so without
 * the widened match the hook is silently skipped forever and the column stays nullable and unpopulated.
 */
class MigrationKillMidGeneratedHookCrashResumeTest {

    @Test
    void aKilledGeneratedHookResumesOnTheNextBootWithNoDataLoss(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.resolve("kill-mid-generated-hook-db").toAbsolutePath().toString();

        HarnessResult crashResult = runHarness(tempDir, "crash-run", "crash", dbPath);
        assertEquals(137, crashResult.exitCode(),
                "the harness must die via Runtime.halt(137), not exit normally: " + crashResult.output());
        assertTrue(crashResult.output().contains("CRASH_HARNESS: ADD COLUMN committed"), crashResult.output());

        // Intermediate state: the ADD COLUMN committed (H2 implicit-commit-on-DDL); the UPDATE backfill
        // and SET NOT NULL never ran -- the exact half-applied window STOR-22 needs the next boot to
        // resume through, not get permanently stuck on.
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE";
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            assertEquals(3L, singleLongQuery(connection, "SELECT COUNT(*) FROM stor22_conv WHERE status IS NULL"),
                    "the UPDATE backfill must NOT have run yet -- every row's status must still be NULL");
        }

        HarnessResult resumeResult = runHarness(tempDir, "resume-run", "resume", dbPath);
        assertEquals(0, resumeResult.exitCode(), "the resumed boot must complete cleanly: " + resumeResult.output());
        assertTrue(resumeResult.output().contains("RESUME_HARNESS: DONE"), resumeResult.output());
        assertTrue(resumeResult.output().contains("HOOK_APPLIED") && resumeResult.output().contains("RESOLVED"),
                "the hook must be re-selected and the boot must resolve, not silently skip it: "
                        + resumeResult.output());

        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            assertEquals(0L, singleLongQuery(connection, "SELECT COUNT(*) FROM stor22_conv WHERE status IS NULL"),
                    "every row must now be backfilled -- no data loss, no row left behind");
            assertEquals(3L, singleLongQuery(connection, "SELECT COUNT(*) FROM stor22_conv WHERE status = 'unknown'"));
            assertEquals(3L, singleLongQuery(connection, "SELECT COUNT(*) FROM stor22_conv"),
                    "no row was lost or duplicated across the crash/resume boundary");
        }
    }

    private record HarnessResult(int exitCode, String output) {
    }

    private HarnessResult runHarness(Path tempDir, String logFileName, String... harnessArgs) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        // Same JDK-argfile approach MigrationKillMidPhaseCrashResumeTest uses -- a generated FinalApp's
        // classpath is long enough to exceed Windows' CreateProcess command-line length limit.
        Path argsFile = tempDir.resolve(logFileName + ".argfile");
        StringBuilder argsContent = new StringBuilder();
        argsContent.append("-cp\n").append(classpath).append('\n');
        argsContent.append("com.finalexec.db.MigrationKillMidGeneratedHookHarness\n");
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
