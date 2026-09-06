package com.finalexec.db;

import com.finalexec.boundary.BoundaryBootException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B31. Ordering/no-op/wiring only, at the {@link org.springframework.boot.env.EnvironmentPostProcessor}
 * level -- no real {@code ApplicationContext} boot. The real cross-process contention proof is
 * {@code H2LocalBootLockCrossProcessTest}; {@link H2LocalBootLockTest} covers the lock primitive
 * itself. This class proves the wiring layer: a non-H2Local engine adds no listener; an H2Local URL
 * naming {@code AUTO_SERVER=FALSE} explicitly (or with the STOR-27 opt-out property set) still adds
 * exactly one {@link ContextClosedEvent} listener that genuinely releases the lock when fired, and a
 * lock failure on that path surfaces as {@link BoundaryBootException} naming boundary B31.
 *
 * <p>STOR-27 (B31 lift): an H2Local URL naming NO {@code AUTO_SERVER} token at all is now rewritten
 * to {@code AUTO_SERVER=TRUE} and the boot lock below is skipped entirely -- see {@link
 * H2LocalAutoServer} and the tests at the bottom of this class. Every test above that boundary keeps
 * exercising the (still real, still needed) opt-out path by pinning {@code AUTO_SERVER=FALSE}
 * explicitly, exactly like the {@code MigrationKillMid*} harnesses.
 */
class H2LocalBootLockEnvironmentPostProcessorTest {

    private static final String WAIT_SECONDS_PROPERTY = "npdev.h2local.bootLock.waitSeconds";

    @TempDir
    Path tempDir;

    private String previousWaitSeconds;
    private final H2LocalBootLockEnvironmentPostProcessor processor = new H2LocalBootLockEnvironmentPostProcessor();

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
    @DisplayName("a non-H2Local engine adds no listener and does not touch the filesystem")
    void nonH2LocalEngineIsANoOp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.database.engine", "Postgres");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://localhost/foo");
        SpringApplication application = new SpringApplication();
        int baselineListenerCount = application.getListeners().size();

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));

        assertEquals(baselineListenerCount, application.getListeners().size());
    }

    @Test
    @DisplayName("H2Local with AUTO_SERVER=FALSE adds exactly one ContextClosedEvent listener, and firing it releases the real lock")
    void h2LocalWithAutoServerDisabledRegistersAReleaseListenerThatActuallyReleases() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL;AUTO_SERVER=FALSE";
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.database.engine", "H2Local");
        environment.setProperty("spring.datasource.url", url);
        SpringApplication application = new SpringApplication();
        Set<ApplicationListener<?>> baselineListeners = Set.copyOf(application.getListeners());

        processor.postProcessEnvironment(environment, application);

        Set<ApplicationListener<?>> listeners = application.getListeners();
        assertEquals(baselineListeners.size() + 1, listeners.size());

        // The lock is genuinely held right now: a second attempt on the same file, from the same
        // JVM, must see contention (OverlappingFileLockException) rather than acquiring cleanly.
        Path lockFilePath = H2LocalBootLock.lockFilePathFor(url);
        try (FileChannel probe = FileChannel.open(lockFilePath, StandardOpenOption.WRITE)) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class, probe::tryLock);
        }

        // Firing the newly-registered listener (simulating the real ApplicationContext eventually
        // publishing ContextClosedEvent) must release it -- and also exercises the exact cleanup
        // path this test needs to leave no lock behind.
        ApplicationListener<?> addedListener = listeners.stream()
                .filter(listener -> !baselineListeners.contains(listener))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        ApplicationListener<ContextClosedEvent> releaseListener =
                (ApplicationListener<ContextClosedEvent>) addedListener;
        try (GenericApplicationContext dummyContext = new GenericApplicationContext()) {
            releaseListener.onApplicationEvent(new ContextClosedEvent(dummyContext));
        }

        // Released: a fresh acquire on the same file now succeeds cleanly.
        H2LocalBootLock.Held reacquired = H2LocalBootLock.acquireIfNeeded("H2Local", url).orElseThrow();
        H2LocalBootLock.release(reacquired);
    }

    @Test
    @DisplayName("a genuine lock timeout on the AUTO_SERVER=FALSE path surfaces as BoundaryBootException naming boundary B31")
    void h2LocalWithAutoServerDisabledLockTimeoutSurfacesAsBoundaryBootException() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL;AUTO_SERVER=FALSE";
        Path lockFilePath = H2LocalBootLock.lockFilePathFor(url);
        Files.createDirectories(lockFilePath.getParent());
        System.setProperty(WAIT_SECONDS_PROPERTY, "1");

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.database.engine", "H2Local");
        environment.setProperty("spring.datasource.url", url);
        SpringApplication application = new SpringApplication();

        try (FileChannel preLocked = FileChannel.open(lockFilePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = preLocked.lock()) {
            BoundaryBootException failure = assertThrows(BoundaryBootException.class,
                    () -> processor.postProcessEnvironment(environment, application));
            assertEquals("B31", failure.getViolation().boundaryId());
            assertTrue(failure.getMessage().startsWith("B31:h2local_boot_lock_held:"));
        }
    }

    // ---- STOR-27 (B31 lift): AUTO_SERVER routing ----

    @Test
    @DisplayName("an H2Local URL naming no AUTO_SERVER token is rewritten to AUTO_SERVER=TRUE and the boot lock is skipped")
    void h2LocalWithNoAutoServerTokenDefaultsToAutoServerAndSkipsTheBootLock() {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.database.engine", "H2Local");
        environment.setProperty("spring.datasource.url", url);
        SpringApplication application = new SpringApplication();
        int baselineListenerCount = application.getListeners().size();

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));

        assertEquals(baselineListenerCount, application.getListeners().size(),
                "AUTO_SERVER=TRUE means H2's own TCP server arbitrates access -- no boot-lock listener needed");
        assertEquals(url + ";AUTO_SERVER=TRUE", environment.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("a real generator-shaped URL (DB_CLOSE_ON_EXIT=FALSE, no AUTO_SERVER token) is "
            + "rewritten with DB_CLOSE_ON_EXIT=FALSE stripped, not just AUTO_SERVER=TRUE appended")
    void h2LocalUrlCarryingDbCloseOnExitFalseHasItStrippedWhenAutoServerIsAppended() {
        // The exact shape UserDatabaseDefinitionLoader.jdbcUrl minted for every H2Local app before
        // STOR-27, and what npdev_cli.py's _jdbc_url_for_verify still mints too -- proven live via
        // run-r10-plugin-controller-proof.py against a probe app carrying this real shape: H2
        // refuses "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE" outright at boot.
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.database.engine", "H2Local");
        environment.setProperty("spring.datasource.url", url);
        SpringApplication application = new SpringApplication();

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));

        String rewritten = environment.getProperty("spring.datasource.url");
        assertTrue(rewritten.toUpperCase(java.util.Locale.ROOT).contains("AUTO_SERVER=TRUE"),
                "AUTO_SERVER=TRUE must still be appended: " + rewritten);
        assertTrue(!rewritten.toUpperCase(java.util.Locale.ROOT).contains("DB_CLOSE_ON_EXIT=FALSE"),
                "DB_CLOSE_ON_EXIT=FALSE must be stripped -- H2 refuses it combined with AUTO_SERVER=TRUE: "
                        + rewritten);
    }

    @Test
    @DisplayName("a URL already naming AUTO_SERVER=FALSE is never rewritten, and the boot lock still applies")
    void h2LocalUrlAlreadyNamingAutoServerFalseIsNeverRewritten() {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL;AUTO_SERVER=FALSE";
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.database.engine", "H2Local");
        environment.setProperty("spring.datasource.url", url);
        SpringApplication application = new SpringApplication();
        int baselineListenerCount = application.getListeners().size();

        processor.postProcessEnvironment(environment, application);

        assertEquals(url, environment.getProperty("spring.datasource.url"), "never touch a URL that already names AUTO_SERVER");
        assertEquals(baselineListenerCount + 1, application.getListeners().size(),
                "AUTO_SERVER=FALSE means the OS-level boot lock still arbitrates access");
    }

    @Test
    @DisplayName("npdev.h2local.autoServer=false opts a no-token URL out of the rewrite and keeps the old boot-lock behavior")
    void autoServerOptOutPropertyKeepsTheOldBootLockBehavior() {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.database.engine", "H2Local");
        environment.setProperty("spring.datasource.url", url);
        environment.setProperty("npdev.h2local.autoServer", "false");
        SpringApplication application = new SpringApplication();
        int baselineListenerCount = application.getListeners().size();

        processor.postProcessEnvironment(environment, application);

        assertEquals(url, environment.getProperty("spring.datasource.url"), "opted out -- never rewritten");
        assertEquals(baselineListenerCount + 1, application.getListeners().size());
    }
}
