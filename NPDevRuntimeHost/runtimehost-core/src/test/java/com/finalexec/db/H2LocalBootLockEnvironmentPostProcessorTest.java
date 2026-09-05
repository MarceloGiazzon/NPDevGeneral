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
 * itself. This class proves the three things specific to the wiring layer: a non-H2Local engine adds
 * no listener, an H2Local engine adds exactly one {@link ContextClosedEvent} listener that genuinely
 * releases the lock when fired, and a lock failure surfaces as {@link BoundaryBootException} naming
 * boundary B31.
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
    @DisplayName("H2Local adds exactly one ContextClosedEvent listener, and firing it releases the real lock")
    void h2LocalRegistersAReleaseListenerThatActuallyReleases() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";
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
    @DisplayName("a genuine lock timeout surfaces as BoundaryBootException naming boundary B31")
    void lockTimeoutSurfacesAsBoundaryBootException() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("mydb") + ";MODE=PostgreSQL";
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
}
