package com.finalexec;

import com.finalexec.config.NpdevObservabilityConfig;
import com.npdev.adapters.runtime.validation.StrictExecutionValidator;
import com.npdev.adapters.runtime.validation.StrictExecutionViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictExecutionStartupValidationIntegrationTest {

    @Test
    void governedModeFailsStartupWhenStrictExecutionIsDisabled() {
        contextRunner(false, System.getProperty("user.dir") + "\\npdev-generated", "governed")
                .run(context -> {
                    Throwable failure = rootCause(context.getStartupFailure());
                    assertNotNull(failure);
                    assertInstanceOf(StrictExecutionViolationException.class, failure);
                    assertTrue(failure.getMessage().contains("cannot be disabled"));
                });
    }

    @Test
    void governedModeFailsStartupWhenGeneratedRootIsUnsigned() throws Exception {
        Path generatedRoot = Files.createTempDirectory("npdev-unsigned-generated-root-");
        Path modelPath = generatedRoot.resolve("src/main/resources/npdev/model.json");
        Files.createDirectories(modelPath.getParent());
        Files.writeString(modelPath, "{\"namespace\":\"strict.execution\"}\n");

        contextRunner(true, escapePath(generatedRoot), "governed")
                .run(context -> {
                    Throwable failure = rootCause(context.getStartupFailure());
                    assertNotNull(failure);
                    assertInstanceOf(StrictExecutionViolationException.class, failure);
                    assertTrue(failure.getMessage().contains("signature is missing"));
                });
    }

    @Test
    void governedModeFailsStartupWhenSurfaceProfileDrifts() throws Exception {
        Path generatedRoot = Files.createTempDirectory("npdev-profile-drift-generated-root-");

        contextRunner(true, escapePath(generatedRoot), "governed", "non-default", true)
                .run(context -> {
                    Throwable failure = rootCause(context.getStartupFailure());
                    assertNotNull(failure);
                    assertInstanceOf(StrictExecutionViolationException.class, failure);
                    assertTrue(failure.getMessage().contains("requires runtime surface profile 'supported-core'"));
                });
    }

    @Test
    void governedModeFailsStartupWhenSupportedSurfaceEnforcementIsDisabled() throws Exception {
        Path generatedRoot = Files.createTempDirectory("npdev-surface-enforcement-generated-root-");

        contextRunner(true, escapePath(generatedRoot), "governed", "supported-core", false)
                .run(context -> {
                    Throwable failure = rootCause(context.getStartupFailure());
                    assertNotNull(failure);
                    assertInstanceOf(StrictExecutionViolationException.class, failure);
                    assertTrue(failure.getMessage().contains("requires supported runtime surface enforcement"));
                });
    }

    @Test
    void relaxedModeAllowsMissingGeneratedRoot() {
        contextRunner(true, "missing-relaxed-root", "relaxed")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    private ApplicationContextRunner contextRunner(
            boolean strictExecutionEnabled,
            String strictExecutionGeneratedRoot,
            String executionMode
    ) {
        return contextRunner(
                strictExecutionEnabled,
                strictExecutionGeneratedRoot,
                executionMode,
                "supported-core",
                true
        );
    }

    private ApplicationContextRunner contextRunner(
            boolean strictExecutionEnabled,
            String strictExecutionGeneratedRoot,
            String executionMode,
            String surfaceProfile,
            boolean supportedSurfaceEnforced
    ) {
        return new ApplicationContextRunner()
                .withBean(
                        StrictExecutionValidator.class,
                        () -> new NpdevObservabilityConfig().strictExecutionValidator(
                                strictExecutionEnabled,
                                strictExecutionGeneratedRoot,
                                executionMode,
                                surfaceProfile,
                                supportedSurfaceEnforced
                        )
                );
    }

    private static Throwable rootCause(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String escapePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }
}
