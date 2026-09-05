package com.finalexec.db;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B31. Two things this class can prove entirely in-process (see
 * {@code H2LocalBootLockCrossProcessTest} for the genuinely cross-PROCESS proof a real rolling
 * restart needs): the URL-parsing edge cases, and that a same-JVM
 * {@link java.nio.channels.OverlappingFileLockException} -- the one contention shape a same-JVM test
 * CAN legitimately produce, since {@link FileChannel#tryLock()} only returns {@code null} for a
 * DIFFERENT process's lock -- is treated as ordinary contention rather than crashing.
 */
class H2LocalBootLockTest {

    private static final String WAIT_SECONDS_PROPERTY = "npdev.h2local.bootLock.waitSeconds";

    @TempDir
    Path tempDir;

    private String previousWaitSeconds;

    @BeforeEach
    void setUp() {
        previousWaitSeconds = System.getProperty(WAIT_SECONDS_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        if (previousWaitSeconds == null) {
            System.clearProperty(WAIT_SECONDS_PROPERTY);
        } else {
            System.setProperty(WAIT_SECONDS_PROPERTY, previousWaitSeconds);
        }
    }

    @Test
    @DisplayName("lockFilePathFor parses a real H2Local URL and strips H2's own ;PARAM=... suffix")
    void lockFilePathForParsesFileUrlAndStripsParams() {
        Path dbFile = tempDir.resolve("mydb");
        Path lockFilePath = H2LocalBootLock.lockFilePathFor(
                "jdbc:h2:file:" + dbFile + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
        assertTrue(lockFilePath != null && lockFilePath.getFileName().toString().equals("mydb.boot.lock"));
    }

    @Test
    @DisplayName("lockFilePathFor no-ops for H2Server/in-memory/blank URLs -- never a refusal")
    void lockFilePathForReturnsNullForNonFileUrls() {
        assertNull(H2LocalBootLock.lockFilePathFor("jdbc:h2:tcp://localhost:9092/foo"));
        assertNull(H2LocalBootLock.lockFilePathFor("jdbc:h2:mem:foo;DB_CLOSE_DELAY=-1"));
        assertNull(H2LocalBootLock.lockFilePathFor("jdbc:postgresql://localhost/foo"));
        assertNull(H2LocalBootLock.lockFilePathFor(null));
        assertNull(H2LocalBootLock.lockFilePathFor("   "));
        assertNull(H2LocalBootLock.lockFilePathFor("jdbc:h2:file:"));
    }

    @Test
    @DisplayName("acquireIfNeeded no-ops for every engine but H2Local")
    void noOpsForNonH2LocalEngines() {
        assertTrue(H2LocalBootLock.acquireIfNeeded("H2Server", "jdbc:h2:tcp://localhost:9092/foo").isEmpty());
        assertTrue(H2LocalBootLock.acquireIfNeeded("Postgres", "jdbc:postgresql://localhost/foo").isEmpty());
        assertTrue(H2LocalBootLock.acquireIfNeeded("", "").isEmpty());
        assertTrue(H2LocalBootLock.acquireIfNeeded(null, null).isEmpty());
    }

    @Test
    @DisplayName("acquires a real OS-level lock, then releases it so a later boot can acquire it again")
    void acquiresAndReleasesRealLock() {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";

        Optional<H2LocalBootLock.Held> first = H2LocalBootLock.acquireIfNeeded("H2Local", url);
        assertTrue(first.isPresent());
        H2LocalBootLock.release(first.get());

        Optional<H2LocalBootLock.Held> second = H2LocalBootLock.acquireIfNeeded("H2Local", url);
        assertTrue(second.isPresent());
        H2LocalBootLock.release(second.get());
    }

    @Test
    @DisplayName("release is idempotent and tolerates null")
    void releaseIsIdempotent() {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";
        H2LocalBootLock.Held held = H2LocalBootLock.acquireIfNeeded("H2Local", url).orElseThrow();
        H2LocalBootLock.release(held);
        assertDoesNotThrow(() -> H2LocalBootLock.release(held));
        assertDoesNotThrow(() -> H2LocalBootLock.release(null));
    }

    @Test
    @DisplayName("a same-JVM OverlappingFileLockException is treated as contention, not a crash, "
            + "and a genuine timeout names boundary B31")
    void treatsSameJvmOverlappingLockAsContentionAndTimesOut() throws IOException {
        Path dbFile = tempDir.resolve("mydb");
        String url = "jdbc:h2:file:" + dbFile + ";MODE=PostgreSQL";
        Path lockFilePath = H2LocalBootLock.lockFilePathFor(url);
        Files.createDirectories(lockFilePath.getParent());

        System.setProperty(WAIT_SECONDS_PROPERTY, "1");
        try (FileChannel preLocked = FileChannel.open(lockFilePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = preLocked.lock()) {
            // A NEW channel on the SAME file, from the SAME JVM: per FileChannel#tryLock's own
            // contract this throws OverlappingFileLockException, never returns null -- the one
            // contention shape a same-JVM test can actually produce.
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> H2LocalBootLock.acquireIfNeeded("H2Local", url));
            assertTrue(failure.getMessage().startsWith("B31:h2local_boot_lock_held:"));
        }
    }
}
