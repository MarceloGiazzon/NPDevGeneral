package com.npdev.adapters.runtime.validation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictExecutionValidatorTest {

    @Test
    void passesWhenGeneratedFolderMatchesExpectedSignature() throws Exception {
        Path generatedRoot = createSignedGeneratedRoot();

        StrictExecutionValidator validator = new StrictExecutionValidator(
                true,
                generatedRoot.toString(),
                StrictExecutionValidator.EXECUTION_MODE_GOVERNED
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void failsWhenGeneratedFolderContainsForbiddenBakFile() throws Exception {
        Path generatedRoot = createSignedGeneratedRoot();
        Files.writeString(generatedRoot.resolve("src/main/resources/npdev/manual-change.bak"), "bak");

        StrictExecutionViolationException exception = assertThrows(
                StrictExecutionViolationException.class,
                () -> new StrictExecutionValidator(
                        true,
                        generatedRoot.toString(),
                        StrictExecutionValidator.EXECUTION_MODE_GOVERNED
                ).validate()
        );

        assertTrue(exception.getMessage().contains("manual-change.bak"));
    }

    @Test
    void failsWhenGeneratedFolderSignatureDoesNotMatch() throws Exception {
        Path generatedRoot = createSignedGeneratedRoot();
        Files.writeString(generatedRoot.resolve("src/main/resources/npdev/model.json"), "{\"namespace\":\"tampered\"}\n");

        StrictExecutionViolationException exception = assertThrows(
                StrictExecutionViolationException.class,
                () -> new StrictExecutionValidator(
                        true,
                        generatedRoot.toString(),
                        StrictExecutionValidator.EXECUTION_MODE_GOVERNED
                ).validate()
        );

        assertTrue(exception.getMessage().contains("signature mismatch"));
    }

    @Test
    void failsWhenGovernedModeUsesNonSupportedCoreSurfaceProfile() throws Exception {
        Path generatedRoot = createSignedGeneratedRoot();

        StrictExecutionViolationException exception = assertThrows(
                StrictExecutionViolationException.class,
                () -> new StrictExecutionValidator(
                        true,
                        generatedRoot.toString(),
                        StrictExecutionValidator.EXECUTION_MODE_GOVERNED,
                        "non-default",
                        true
                ).validate()
        );

        assertTrue(exception.getMessage().contains("requires runtime surface profile 'supported-core'"));
    }

    @Test
    void failsWhenGovernedModeDoesNotEnforceSupportedRuntimeSurface() throws Exception {
        Path generatedRoot = createSignedGeneratedRoot();

        StrictExecutionViolationException exception = assertThrows(
                StrictExecutionViolationException.class,
                () -> new StrictExecutionValidator(
                        true,
                        generatedRoot.toString(),
                        StrictExecutionValidator.EXECUTION_MODE_GOVERNED,
                        StrictExecutionValidator.SURFACE_PROFILE_SUPPORTED_CORE,
                        false
                ).validate()
        );

        assertTrue(exception.getMessage().contains("requires supported runtime surface enforcement"));
    }

    @Test
    void failsWhenGovernedGeneratedRootContainsUnsupportedSurfaceArtifacts() throws Exception {
        Path generatedRoot = createSignedGeneratedRoot();
        Files.writeString(generatedRoot.resolve("src/main/resources/npdev/runtime-unsupported-controllers.json"), "{}\n");

        StrictExecutionViolationException exception = assertThrows(
                StrictExecutionViolationException.class,
                () -> new StrictExecutionValidator(
                        true,
                        generatedRoot.toString(),
                        StrictExecutionValidator.EXECUTION_MODE_GOVERNED
                ).validate()
        );

        assertTrue(exception.getMessage().contains("unsupported runtime surface artifacts"));
    }

    @Test
    void failsWhenGovernedModeDisablesStrictExecution() {
        StrictExecutionViolationException exception = assertThrows(
                StrictExecutionViolationException.class,
                () -> new StrictExecutionValidator(
                        false,
                        "",
                        StrictExecutionValidator.EXECUTION_MODE_GOVERNED
                ).validate()
        );

        assertTrue(exception.getMessage().contains("cannot be disabled"));
    }

    @Test
    void failsWhenGovernedModeGeneratedRootIsMissing() {
        Path missingRoot = Path.of("missing-governed-generated-root");

        StrictExecutionViolationException exception = assertThrows(
                StrictExecutionViolationException.class,
                () -> new StrictExecutionValidator(
                        true,
                        missingRoot.toString(),
                        StrictExecutionValidator.EXECUTION_MODE_GOVERNED
                ).validate()
        );

        assertTrue(exception.getMessage().contains("requires a generated root"));
    }

    @Test
    void allowsMissingGeneratedRootInRelaxedMode() {
        assertDoesNotThrow(() -> new StrictExecutionValidator(
                true,
                "missing-relaxed-generated-root",
                StrictExecutionValidator.EXECUTION_MODE_RELAXED
        ).validate());
    }

    @Test
    void allowsDisabledStrictExecutionInRelaxedMode() {
        assertDoesNotThrow(() -> new StrictExecutionValidator(
                false,
                "",
                StrictExecutionValidator.EXECUTION_MODE_RELAXED
        ).validate());
    }

    private static Path createSignedGeneratedRoot() throws Exception {
        Path generatedRoot = Files.createTempDirectory("npdev-strict-execution-");
        write(generatedRoot.resolve("src/main/resources/npdev/model.json"), "{\"namespace\":\"demo\"}\n");
        write(generatedRoot.resolve("src/main/resources/npdev/compiled-model.json"), "{\"concepts\":[]}\n");
        write(generatedRoot.resolve("src/main/java/com/npdev/generated/runtime/api/AdminController.java"), "class AdminController {}\n");

        GeneratedFolderSignature signature = GeneratedFolderSignature.capture(generatedRoot);
        signature.write(generatedRoot.resolve(GeneratedFolderSignature.SIGNATURE_RELATIVE_PATH));
        return generatedRoot;
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
