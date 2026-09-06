package com.finalexec.db;

import com.finalexec.boundary.BoundaryBootException;
import com.finalexec.boundary.BoundaryViolation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * B31: registered in {@code META-INF/spring.factories} as an {@link EnvironmentPostProcessor} --
 * Spring Boot's own SPI for "run once the Environment is prepared, before any bean is created" --
 * specifically so this runs BEFORE the DataSource bean (and Hikari's own eager connection test) exists.
 * See {@link H2LocalBootLock}'s javadoc for why that ordering is the whole point: on H2Local, the
 * "another process already has this file open" failure happens during DataSource bean construction,
 * strictly earlier than {@code SchemaLifecycleExecutor}/{@code MigrationMutex} ever run.
 *
 * <p>Deliberately does NOT implement {@link org.springframework.core.Ordered} -- it defaults to
 * lowest precedence, which is required here: {@code ConfigDataEnvironmentPostProcessor} (itself an
 * {@code EnvironmentPostProcessor}, at {@code HIGHEST_PRECEDENCE + 10}) has to resolve
 * {@code application-npdev-db.properties} FIRST, or {@code npdev.database.engine}/
 * {@code spring.datasource.url} would still read blank here.
 *
 * <p>Requires zero changes to {@code FinalExecApplication.java} (copied verbatim into every generated
 * app): a thrown {@link BoundaryBootException} propagates untouched through
 * {@code SpringApplication.run()}'s failure path straight to that class's existing
 * {@code findBoundaryBootException}/exit-code-4 handling, and
 * {@code BoundaryBootExceptionFailureAnalyzer} runs correctly even this early (it has no constructor
 * dependencies on the {@code ApplicationContext}, which does not exist yet at this point).
 */
public class H2LocalBootLockEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String engine = environment.getProperty("npdev.database.engine", "");
        String jdbcUrl = environment.getProperty("spring.datasource.url", "");

        // STOR-27 (B31 lift): rewrite the URL to AUTO_SERVER=TRUE before any bean (Hikari's DataSource
        // included) ever reads it -- the one seam early enough for every later reader to see the
        // rewritten value. Never touches a URL that already names AUTO_SERVER (either way).
        boolean autoServerEnabled = H2LocalAutoServer.enabledByDefault(environment);
        Optional<String> rewritten = H2LocalAutoServer.rewriteIfEligible(engine, jdbcUrl, autoServerEnabled);
        if (rewritten.isPresent()) {
            jdbcUrl = rewritten.get();
            Map<String, Object> override = new LinkedHashMap<>();
            override.put("spring.datasource.url", jdbcUrl);
            environment.getPropertySources().addFirst(new MapPropertySource("npdevH2LocalAutoServer", override));
        }

        if (H2LocalAutoServer.isAutoServerActive(jdbcUrl)) {
            // H2's own TCP server now arbitrates multi-process access to this file -- the OS-level
            // boot lock below exists only for the opt-out/legacy AUTO_SERVER=FALSE case.
            return;
        }

        Optional<H2LocalBootLock.Held> acquired;
        try {
            acquired = H2LocalBootLock.acquireIfNeeded(engine, jdbcUrl);
        } catch (IllegalStateException lockFailure) {
            throw new BoundaryBootException(
                    new BoundaryViolation("B31", "boot", lockFailure.getMessage(), Instant.now()), lockFailure);
        }
        if (acquired.isEmpty()) {
            return;
        }
        H2LocalBootLock.Held held = acquired.get();
        // ContextClosedEvent covers both graceful shutdown (a registered shutdown hook closes the
        // context, which publishes this event) and the case where some other bean fails later in the
        // SAME boot (Spring's own failure handling still closes a context that was created). The JVM
        // shutdown hook below is only a fallback for "the context was never created at all" -- release
        // is idempotent, so running both is safe.
        application.addListeners(
                (ApplicationListener<ContextClosedEvent>) event -> H2LocalBootLock.release(held));
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> H2LocalBootLock.release(held), "npdev-h2local-boot-lock-release"));
    }
}
