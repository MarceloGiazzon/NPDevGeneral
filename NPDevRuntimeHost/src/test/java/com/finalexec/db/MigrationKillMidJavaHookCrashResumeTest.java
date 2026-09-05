package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifest;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifestLoader;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B1 (REAL_LIFT_PLAN_2026-09-03, B13 done-when #4): "a killed child resumes on the next boot rather
 * than stranding the migration" -- proven with a REAL killed child JVM process mid-javaHook-migration
 * (not a simulated exception), mirroring {@code MigrationKillMidPhaseCrashResumeTest}'s own A1 proof
 * shape exactly, applied to {@link JavaMigrationHookRunner}'s batch loop instead of {@code
 * ConversionHookPhaseRunner}'s phase loop.
 *
 * <p>Requires the currently assembled sample app to be dsl-conformance-max (its {@code
 * OrderSummaryHook} + {@code java-source-runtime-refs.json} entry must be on this process's own
 * classpath, which the forked harness process inherits) -- skips gracefully otherwise, same {@code
 * assumeTrue} convention {@code PluginIpcCapabilityHandlerRealPluginTest} uses.
 */
class MigrationKillMidJavaHookCrashResumeTest {

    private static final String HOOK_ID = "0008-java-hook-order-summary";

    @Test
    void aKilledJavaHookMigrationResumesOnTheNextBootWithNoDataLossOrDuplication(@TempDir Path tempDir) throws Exception {
        JavaSourceRuntimeRefManifest manifest = new JavaSourceRuntimeRefManifestLoader(new ObjectMapper()).load();
        Optional<JavaSourceRuntimeRefManifest.Entry> entry = manifest.entryForRuntimeRef(HOOK_ID);
        assumeTrue(
                entry.isPresent(),
                "No '" + HOOK_ID + "' entry in java-source-runtime-refs.json -- the currently assembled sample "
                        + "app is not dsl-conformance-max, so this real-crash proof has nothing to run against. "
                        + "Regenerate against dsl-conformance-max to exercise it."
        );
        String claimKey = requireClaimKey();
        String[] claimParts = claimKey.split(":", 3);
        String table = claimParts[1];
        String column = claimParts[2];

        String dbPath = tempDir.resolve("java-hook-kill-mid-batch-db").toAbsolutePath().toString();

        // 4 rows total, batch size forced to 1 by the harness -> exactly 4 batches. Crash after 2.
        HarnessResult crashResult = runHarness(tempDir, "crash-run", "crash", dbPath, "2");
        assertEquals(137, crashResult.exitCode(),
                "the harness must die via Runtime.halt(137), not exit normally: " + crashResult.output());
        assertTrue(crashResult.output().contains("CRASH_HARNESS: completed maxBatches=2; halting now."),
                crashResult.output());

        // Intermediate state: exactly 2 of 4 rows have the claimed column populated; the other 2 are
        // still unclaimed -- the exact half-applied window a killed migration must resume THROUGH.
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE";
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            assertEquals(2L, singleLongQuery(connection, "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NULL"),
                    "exactly 2 of 4 rows must still be unclaimed after a crash mid-migration (batch size 1, "
                            + "crashed after 2 batches)");
            assertEquals(2L, singleLongQuery(connection, "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NOT NULL"));
        }

        HarnessResult resumeResult = runHarness(tempDir, "resume-run", "resume", dbPath);
        assertEquals(0, resumeResult.exitCode(), "the resumed boot must complete cleanly: " + resumeResult.output());
        assertTrue(resumeResult.output().contains("RESUME_HARNESS: DONE"), resumeResult.output());

        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            assertEquals(0L, singleLongQuery(connection, "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NULL"),
                    "every row must now be backfilled -- no row left behind across the crash/resume boundary");
            assertEquals(4L, singleLongQuery(connection, "SELECT COUNT(*) FROM " + table),
                    "no row was lost or duplicated across the crash/resume boundary");
            // Real OrderSummaryHook logic: priority <= 1 -> URGENT, else STANDARD.
            assertEquals(1L, singleLongQuery(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE priority_number = 1 AND " + column + " = 'URGENT-Region1'"));
            assertEquals(3L, singleLongQuery(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE priority_number > 1 AND " + column + " LIKE 'STANDARD-%'"));
        }
    }

    private static String requireClaimKey() {
        for (var entry : ConversionHookRunner.loadClaimIndex().entrySet()) {
            if (HOOK_ID.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("No claim indexed for hook '" + HOOK_ID + "'.");
    }

    private record HarnessResult(int exitCode, String output) {
    }

    private HarnessResult runHarness(Path tempDir, String logFileName, String... harnessArgs) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        // Same Windows CreateProcess command-line-length workaround MigrationKillMidPhaseCrashResumeTest
        // uses -- a JDK argument file, immune to the limit a direct `-cp <classpath>` can hit.
        Path argsFile = tempDir.resolve(logFileName + ".argfile");
        StringBuilder argsContent = new StringBuilder();
        argsContent.append("-cp\n").append(classpath).append('\n');
        argsContent.append("com.finalexec.db.MigrationKillMidJavaHookHarness\n");
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
