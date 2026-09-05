package com.finalexec.db;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;

/**
 * B31: the multi-process boot gap H2Local (embedded file) mode has always had, closed the same way
 * {@link MigrationMutex} closes it for every other engine/mode -- a graceful bounded wait instead of a
 * hard crash.
 *
 * <h2>Why H2Local is different from every other engine MigrationMutex already covers</h2>
 *
 * <p>Postgres/MySQL/SQL Server use a session advisory lock; H2Server (a real always-up TCP server
 * multiple app instances connect to, exactly like those three) uses a row lock -- both proven safe
 * under real two-PROCESS contention ({@code MigrationLockConcurrentBootTest}). H2Local
 * ({@code jdbc:h2:file:...}) is a raw embedded-file connection with no server process arbitrating
 * access: if a second OS process opens the same file while a first still holds it (the realistic case
 * is a rolling restart's brief overlap, not two permanent instances -- H2Local is inherently
 * single-process for its whole lifetime, not just during migration), H2 itself throws "database
 * already in use" on that second process's very first {@code DataSource.getConnection()} call. That
 * call happens inside Spring's own DataSource bean construction during {@code ApplicationContext}
 * refresh -- before {@code SchemaLifecycleExecutor}/{@code MigrationMutex} ever run -- so today it
 * surfaces as a raw, uncaught {@code BeanCreationException}, not a wait or a clean diagnostic.
 *
 * <h2>Why an OS-level file lock, not a database-level one</h2>
 *
 * <p>The whole reason {@code MigrationMutex}'s row-lock fallback works for H2Server is that Flyway's
 * schema stays reachable to bootstrap a lock table in. Here, the failure happens before ANY JDBC
 * connection to the database file can even be attempted -- so the coordination has to live entirely
 * outside the database, in a small sidecar file next to it, acquired via
 * {@link FileChannel#tryLock()}. This is intentionally the platform's OWN exclusion mechanism, not a
 * new database, migration table, or protocol.
 *
 * <h2>Held for the app's whole lifetime, not just the migration window</h2>
 *
 * <p>Unlike {@code MigrationMutex} (which releases the instant migration finishes, so normal request
 * traffic from multiple instances can proceed against a shared server), this lock is held until the
 * process exits. That is not an over-broad restriction: H2Local already permits only one live process
 * against a given file for its ENTIRE run, not only during migration -- this class just makes that
 * existing constraint visible as a graceful wait with a named diagnostic instead of a crash.
 *
 * <h2>Release: OS-level, deliberately not a lease</h2>
 *
 * <p>{@link #release} is idempotent and safe to call more than once (see {@link Held#channel()}'s own
 * {@code isOpen()} guard) because it is invoked from two places -- a graceful {@code ContextClosedEvent}
 * and a JVM shutdown-hook fallback -- and a crash releases the underlying OS lock automatically the
 * moment the process dies, exactly like {@code MigrationMutex}'s connection-scoped release.
 */
public final class H2LocalBootLock {

    private static final String ENGINE_H2LOCAL = "H2Local";
    private static final String JDBC_H2_FILE_PREFIX = "jdbc:h2:file:";
    private static final String LOCK_FILE_SUFFIX = ".boot.lock";

    /**
     * Deliberately its OWN property, not {@code npdev.schema.lock.waitSeconds} -- a migration "has no
     * safe maximum duration" (see {@link MigrationMutex}'s own javadoc), but waiting for a rolling
     * restart's old instance to finish exiting and release a file handle should be near-instant.
     * Sharing the budget would mean an operator who raises the migration wait to survive a
     * legitimately slow migration also silently makes every boot wait far longer than sensible on a
     * genuinely stuck file lock.
     */
    private static final String WAIT_SECONDS_PROPERTY = "npdev.h2local.bootLock.waitSeconds";
    private static final long DEFAULT_WAIT_SECONDS = 30L;

    /** Probe interval. A boot-time operation taken once, so a coarse poll costs nothing. */
    private static final long POLL_MILLIS = 100L;

    private H2LocalBootLock() {
    }

    /** The live lock: holds the channel it was taken on, since closing that channel is what releases
     *  an OS-level file lock regardless of exactly how {@link #release} is invoked. */
    public record Held(FileLock lock, FileChannel channel, Path lockFilePath) {
    }

    /**
     * No-ops (returns {@link Optional#empty()}) for every engine but H2Local, and for an H2Local
     * engine whose JDBC URL does not parse as a file URL -- both mean "nothing to lock", never a
     * refusal. Blocks until the lock is acquired or the wait budget runs out for a genuine H2Local
     * file URL.
     *
     * @throws IllegalStateException naming boundary B31, on a genuine timeout
     */
    public static Optional<Held> acquireIfNeeded(String engine, String jdbcUrl) {
        if (!ENGINE_H2LOCAL.equalsIgnoreCase(engine == null ? "" : engine.trim())) {
            return Optional.empty();
        }
        Path lockFilePath = lockFilePathFor(jdbcUrl);
        if (lockFilePath == null) {
            return Optional.empty();
        }
        return Optional.of(acquire(lockFilePath));
    }

    /**
     * Derives the sidecar lock path from an {@code jdbc:h2:file:...} URL: strips the prefix and
     * everything from the first {@code ;} onward (H2's own parameter separator), then sits the lock
     * file beside the resolved database file rather than inside it -- created once and NEVER deleted.
     * Windows' open-file-delete semantics make cleanup unsafe, so this is a permanent zero-byte
     * marker, the same spirit as H2's own historical {@code .lock.db} files.
     */
    static Path lockFilePathFor(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String trimmed = jdbcUrl.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(JDBC_H2_FILE_PREFIX)) {
            return null;
        }
        String rest = trimmed.substring(JDBC_H2_FILE_PREFIX.length());
        int semicolon = rest.indexOf(';');
        String filePath = semicolon >= 0 ? rest.substring(0, semicolon) : rest;
        if (filePath.isBlank()) {
            return null;
        }
        Path dbPath = Paths.get(filePath).toAbsolutePath().normalize();
        return dbPath.resolveSibling(dbPath.getFileName().toString() + LOCK_FILE_SUFFIX);
    }

    static Held acquire(Path lockFilePath) {
        long budgetMillis = waitMillis();
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        boolean waited = false;
        FileChannel channel = null;
        try {
            if (lockFilePath.getParent() != null) {
                Files.createDirectories(lockFilePath.getParent());
            }
            channel = FileChannel.open(lockFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            while (true) {
                FileLock lock = tryLockQuietly(channel);
                if (lock != null) {
                    if (waited) {
                        System.out.println("NPDev boot: another process released the H2 (file) database at "
                                + lockFilePath + "; acquired the boot lock -- continuing normally.");
                    }
                    Held held = new Held(lock, channel, lockFilePath);
                    return held;
                }
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(waitedOutMessage(budgetMillis, lockFilePath));
                }
                if (!waited) {
                    System.out.println("NPDev boot: another process currently holds the H2 (file) database at "
                            + lockFilePath + "; waiting for it to release (e.g. a rolling restart's old "
                            + "instance still shutting down) rather than failing immediately.");
                }
                waited = true;
                Thread.sleep(POLL_MILLIS);
            }
        } catch (IOException failure) {
            closeQuietly(channel);
            throw new IllegalStateException("Failed to acquire the H2 (file) boot lock at " + lockFilePath
                    + ": " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            closeQuietly(channel);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the H2 (file) boot lock at "
                    + lockFilePath, interrupted);
        } catch (RuntimeException failure) {
            closeQuietly(channel);
            throw failure;
        }
    }

    /**
     * Treats a same-JVM {@link OverlappingFileLockException} identically to a {@code null} return
     * (genuine cross-process contention): only an OS-level lock actually distinguishes "another
     * process" from "another thread/channel in this same JVM already holds it", and the latter is a
     * real, non-buggy scenario (a cached {@code @SpringBootTest} context reusing the same H2Local file
     * against a fresh context in the same test JVM) that must retry, not crash.
     */
    private static FileLock tryLockQuietly(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException alreadyHeldInThisJvm) {
            return null;
        }
    }

    /** Releases the lock. Safe to call more than once, and safe to call with {@code null}. */
    public static void release(Held held) {
        if (held == null || !held.channel().isOpen()) {
            return;
        }
        try {
            held.lock().release();
        } catch (IOException | RuntimeException failure) {
            System.out.println("NPDev boot: releasing the H2 (file) boot lock explicitly failed ("
                    + failure.getMessage() + "); closing its channel below still releases it, since the lock "
                    + "is scoped to that channel at the OS level.");
        } finally {
            closeQuietly(held.channel());
        }
    }

    private static long waitMillis() {
        String configured = System.getProperty(WAIT_SECONDS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_WAIT_SECONDS * 1000L;
        }
        try {
            return Math.max(0L, Long.parseLong(configured.trim())) * 1000L;
        } catch (NumberFormatException ignored) {
            System.out.println("NPDev boot: ignoring unparseable " + WAIT_SECONDS_PROPERTY
                    + "='" + configured + "'; using the default " + DEFAULT_WAIT_SECONDS + "s.");
            return DEFAULT_WAIT_SECONDS * 1000L;
        }
    }

    private static String waitedOutMessage(long budgetMillis, Path lockFilePath) {
        return "B31:h2local_boot_lock_held:Another process is currently using this H2 (file) database ("
                + lockFilePath + "), and this boot timed out after " + (budgetMillis / 1000L) + "s waiting "
                + "for it to release. This is expected during a rolling restart's brief overlap and normally "
                + "resolves once the old instance finishes exiting; raise the budget with -D"
                + WAIT_SECONDS_PROPERTY + "=<seconds> if restarts legitimately take longer, or confirm no "
                + "other instance is stuck running against this database file.";
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // best-effort cleanup only
        }
    }
}
