package com.finalexec.db;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * B31: the genuine cross-PROCESS proof. {@code H2LocalBootLockTest}/
 * {@code H2LocalBootLockEnvironmentPostProcessorTest} only exercise same-JVM contention
 * ({@link java.nio.channels.OverlappingFileLockException}), which is NOT what a real rolling restart
 * produces -- {@link java.nio.channels.FileChannel#tryLock()} only returns {@code null} for a
 * genuinely different OS process. This spawns two REAL {@code java} processes against the SAME
 * H2Local file (mirrors {@code MigrationKillMidPhaseCrashResumeTest}'s child-process/argfile
 * mechanics, adapted to run the two processes CONCURRENTLY rather than sequentially, since the whole
 * point here is proving genuine overlap, not a crash-then-resume sequence).
 */
class H2LocalBootLockCrossProcessTest {

    @Test
    void contenderWaitsThenSucceedsOnceTheHolderReleases(@TempDir Path tempDir) throws Exception {
        String jdbcUrl = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";
        Path releaseSignal = tempDir.resolve("release.signal");

        File holderLog = tempDir.resolve("holder.log").toFile();
        Process holder = startHarness(tempDir, "holder", holderLog, "hold", jdbcUrl,
                releaseSignal.toAbsolutePath().toString());
        try {
            waitForLogToContain(holderLog, "HARNESS: ACQUIRED", Duration.ofSeconds(20));

            File contenderLog = tempDir.resolve("contender.log").toFile();
            Process contender = startHarness(tempDir, "contender", contenderLog, "contend", jdbcUrl, "20");
            try {
                // Proves genuine concurrent contention, not a race that happened to serialize cleanly:
                waitForLogToContain(contenderLog, "another process currently holds", Duration.ofSeconds(20));
                assertFalse(readQuietly(contenderLog).contains("HARNESS: ACQUIRED"),
                        "contender must not have acquired yet -- the holder is still running: "
                                + readQuietly(contenderLog));

                Files.writeString(releaseSignal, "release");

                waitForExit(holder, Duration.ofSeconds(20));
                assertEquals(0, holder.exitValue(), readQuietly(holderLog));
                assertTrue(readQuietly(holderLog).contains("HARNESS: RELEASED"), readQuietly(holderLog));

                waitForExit(contender, Duration.ofSeconds(20));
                assertEquals(0, contender.exitValue(), readQuietly(contenderLog));
                assertTrue(readQuietly(contenderLog).contains("HARNESS: ACQUIRED"), readQuietly(contenderLog));
            } finally {
                contender.destroyForcibly();
            }
        } finally {
            holder.destroyForcibly();
        }
    }

    @Test
    void contenderTimesOutAndRefusesWithTheNamedBoundary(@TempDir Path tempDir) throws Exception {
        String jdbcUrl = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";
        Path releaseSignal = tempDir.resolve("release.signal"); // never written -- holder never releases

        File holderLog = tempDir.resolve("holder.log").toFile();
        Process holder = startHarness(tempDir, "holder", holderLog, "hold", jdbcUrl,
                releaseSignal.toAbsolutePath().toString());
        try {
            waitForLogToContain(holderLog, "HARNESS: ACQUIRED", Duration.ofSeconds(20));

            File contenderLog = tempDir.resolve("contender.log").toFile();
            Process contender = startHarness(tempDir, "contender", contenderLog, "contend", jdbcUrl, "1");
            try {
                waitForExit(contender, Duration.ofSeconds(20));
                assertEquals(4, contender.exitValue(), readQuietly(contenderLog));
                String output = readQuietly(contenderLog);
                assertTrue(output.contains("HARNESS: FAILED B31:h2local_boot_lock_held:"), output);
            } finally {
                contender.destroyForcibly();
            }
        } finally {
            holder.destroyForcibly();
        }
    }

    private Process startHarness(Path tempDir, String label, File logFile, String... harnessArgs) throws IOException {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        // Same JDK-argfile technique as MigrationKillMidPhaseCrashResumeTest, for the same reason: a
        // generated app's full classpath exceeds Windows' CreateProcess command-line length limit
        // when passed directly as `-cp <classpath>`.
        Path argsFile = tempDir.resolve(label + ".argfile");
        StringBuilder argsContent = new StringBuilder();
        argsContent.append("-cp\n").append(classpath).append('\n');
        argsContent.append("com.finalexec.db.H2LocalBootLockHarness\n");
        for (String arg : harnessArgs) {
            argsContent.append(arg).append('\n');
        }
        Files.writeString(argsFile, argsContent.toString());

        List<String> command = List.of(javaBin, "@" + argsFile.toAbsolutePath());
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile).start();
        } catch (IOException startFailure) {
            throw new IOException("failed starting harness child process (javaBin=" + javaBin
                    + ", classpath length=" + classpath.length() + " chars, argsFile=" + argsFile + "): "
                    + startFailure.getMessage(), startFailure);
        }
    }

    private void waitForLogToContain(File logFile, String marker, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (readQuietly(logFile).contains(marker)) {
                return;
            }
            Thread.sleep(100L);
        }
        fail("timed out waiting for '" + marker + "' in " + logFile + "; last content: " + readQuietly(logFile));
    }

    private void waitForExit(Process process, Duration timeout) throws InterruptedException {
        boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("harness process did not exit within " + timeout);
        }
    }

    private String readQuietly(File logFile) {
        try {
            return logFile.exists() ? Files.readString(logFile.toPath()) : "";
        } catch (IOException ignored) {
            return "";
        }
    }
}
