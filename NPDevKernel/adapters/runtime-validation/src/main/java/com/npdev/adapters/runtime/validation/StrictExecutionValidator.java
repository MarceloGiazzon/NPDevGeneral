package com.npdev.adapters.runtime.validation;

import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class StrictExecutionValidator implements InitializingBean {

    public static final String EXECUTION_MODE_GOVERNED = "governed";
    public static final String EXECUTION_MODE_RELAXED = "relaxed";
    public static final String SURFACE_PROFILE_SUPPORTED_CORE = "supported-core";

    private final boolean enabled;
    private final String generatedRootPath;
    private final String executionMode;
    private final String surfaceProfile;
    private final boolean supportedSurfaceEnforced;

    public StrictExecutionValidator(boolean enabled, String generatedRootPath) {
        this(enabled, generatedRootPath, EXECUTION_MODE_GOVERNED);
    }

    public StrictExecutionValidator(boolean enabled, String generatedRootPath, String executionMode) {
        this(enabled, generatedRootPath, executionMode, SURFACE_PROFILE_SUPPORTED_CORE, true);
    }

    public StrictExecutionValidator(
            boolean enabled,
            String generatedRootPath,
            String executionMode,
            String surfaceProfile,
            boolean supportedSurfaceEnforced
    ) {
        this.enabled = enabled;
        this.generatedRootPath = generatedRootPath;
        this.executionMode = executionMode;
        this.surfaceProfile = surfaceProfile;
        this.supportedSurfaceEnforced = supportedSurfaceEnforced;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        String normalizedExecutionMode = normalizeExecutionMode(executionMode);
        if (!enabled) {
            if (isGovernedMode(normalizedExecutionMode)) {
                throw new StrictExecutionViolationException(
                        "Strict execution cannot be disabled while execution mode is governed."
                );
            }
            return;
        }
        if (isGovernedMode(normalizedExecutionMode)) {
            validateGovernedRuntimeProfile();
        }
        Path generatedRoot = resolveGeneratedRoot(generatedRootPath);
        if (generatedRoot == null || !Files.isDirectory(generatedRoot)) {
            if (isGovernedMode(normalizedExecutionMode)) {
                throw new StrictExecutionViolationException(
                        "Strict execution requires a generated root in governed mode: "
                                + Objects.requireNonNullElse(generatedRootPath, "<unset>")
                );
            }
            return;
        }

        List<String> forbiddenFiles = detectForbiddenFiles(generatedRoot);
        if (!forbiddenFiles.isEmpty()) {
            throw new StrictExecutionViolationException(
                    "Strict execution refused to start because generated artifacts contain forbidden files: "
                            + String.join(", ", forbiddenFiles)
            );
        }

        List<String> unsupportedSurfaceArtifacts = detectUnsupportedSurfaceArtifacts(generatedRoot);
        if (!unsupportedSurfaceArtifacts.isEmpty()) {
            throw new StrictExecutionViolationException(
                    "Strict execution refused to start because governed generated artifacts contain unsupported runtime surface artifacts: "
                            + String.join(", ", unsupportedSurfaceArtifacts)
            );
        }

        Path signaturePath = generatedRoot.resolve(GeneratedFolderSignature.SIGNATURE_RELATIVE_PATH);
        if (!Files.isRegularFile(signaturePath)) {
            throw new StrictExecutionViolationException(
                    "Strict execution signature is missing: " + signaturePath.toAbsolutePath().normalize()
            );
        }

        try {
            GeneratedFolderSignature expected = GeneratedFolderSignature.load(signaturePath);
            GeneratedFolderSignature actual = GeneratedFolderSignature.capture(generatedRoot);
            List<String> differences = expected.diffAgainst(actual);
            if (!differences.isEmpty()) {
                throw new StrictExecutionViolationException(
                        "Strict execution signature mismatch under "
                                + generatedRoot.toAbsolutePath().normalize()
                                + ": "
                                + String.join("; ", differences.subList(0, Math.min(5, differences.size())))
                );
            }
        } catch (StrictExecutionViolationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StrictExecutionViolationException(
                    "Strict execution validation failed for " + generatedRoot.toAbsolutePath().normalize(),
                    exception
            );
        }
    }

    private void validateGovernedRuntimeProfile() {
        String normalizedSurfaceProfile = normalizeSurfaceProfile(surfaceProfile);
        if (!SURFACE_PROFILE_SUPPORTED_CORE.equals(normalizedSurfaceProfile)) {
            throw new StrictExecutionViolationException(
                    "Governed strict execution requires runtime surface profile 'supported-core' but found: "
                            + Objects.requireNonNullElse(surfaceProfile, "<unset>")
            );
        }
        if (!supportedSurfaceEnforced) {
            throw new StrictExecutionViolationException(
                    "Governed strict execution requires supported runtime surface enforcement to be enabled."
            );
        }
    }

    private static Path resolveGeneratedRoot(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            return null;
        }
        return Path.of(rootPath.trim()).toAbsolutePath().normalize();
    }

    private static boolean isGovernedMode(String normalizedExecutionMode) {
        return EXECUTION_MODE_GOVERNED.equals(normalizedExecutionMode);
    }

    private static String normalizeExecutionMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return EXECUTION_MODE_GOVERNED;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if (EXECUTION_MODE_GOVERNED.equals(normalized) || EXECUTION_MODE_RELAXED.equals(normalized)) {
            return normalized;
        }
        throw new StrictExecutionViolationException("Unsupported execution mode for strict execution: " + mode);
    }

    private static String normalizeSurfaceProfile(String value) {
        if (value == null || value.isBlank()) {
            return SURFACE_PROFILE_SUPPORTED_CORE;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> detectForbiddenFiles(Path generatedRoot) {
        List<String> forbidden = new ArrayList<>();
        try (var stream = Files.walk(generatedRoot)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> generatedRoot.relativize(path).toString().replace('\\', '/'))
                    .filter(StrictExecutionValidator::isForbidden)
                    .sorted()
                    .forEach(forbidden::add);
        } catch (IOException exception) {
            throw new StrictExecutionViolationException(
                    "Strict execution could not inspect generated artifacts at "
                            + generatedRoot.toAbsolutePath().normalize(),
                    exception
            );
        }
        return forbidden;
    }

    private static List<String> detectUnsupportedSurfaceArtifacts(Path generatedRoot) {
        List<String> unsupportedSurfaceArtifacts = new ArrayList<>();
        try (var stream = Files.walk(generatedRoot)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> generatedRoot.relativize(path).toString().replace('\\', '/'))
                    .filter(StrictExecutionValidator::isUnsupportedSurfaceArtifact)
                    .sorted()
                    .forEach(unsupportedSurfaceArtifacts::add);
        } catch (IOException exception) {
            throw new StrictExecutionViolationException(
                    "Strict execution could not inspect generated runtime surface artifacts at "
                            + generatedRoot.toAbsolutePath().normalize(),
                    exception
            );
        }
        return unsupportedSurfaceArtifacts;
    }

    private static boolean isForbidden(String relativePath) {
        String lower = Objects.requireNonNullElse(relativePath, "").toLowerCase(Locale.ROOT);
        return lower.endsWith(".bak")
                || lower.endsWith(".orig")
                || lower.endsWith(".rej")
                || lower.endsWith(".tmp")
                || lower.endsWith(".patch")
                || lower.endsWith(".diff");
    }

    private static boolean isUnsupportedSurfaceArtifact(String relativePath) {
        String lower = Objects.requireNonNullElse(relativePath, "").toLowerCase(Locale.ROOT);
        return lower.contains("runtime-unsupported")
                || lower.contains("unsupported-runtime-surface")
                || lower.contains("unsupported-surface")
                || lower.contains("non-default-surface")
                || lower.contains("experimental-surface");
    }
}
