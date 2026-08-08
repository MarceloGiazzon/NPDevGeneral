package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins generate-time validation of {@code config.json}.
 *
 * <p><b>Two assertions, and the second is the load-bearing one.</b> That a bad config is refused is
 * the obvious half. That the SHIPPED canary -- and the whole corpus behind it -- is accepted is what
 * makes the check safe to have at all: until 2026-08-08 nothing validated a config.json, so turning
 * enforcement on found 93 violations across 27 files, most of them the schema being wrong rather than
 * the configs. A check that refuses working input is uninstalled within a day, and the version of
 * this class that fires on {@code npdev-canary} would be exactly that.
 */
@DisplayName("config.json contract -- enforced at generation time")
class ConfigSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the shipped canary config is accepted")
    void shippedCanaryIsValid() throws Exception {
        Path canary = repoRoot().resolve("NPDevSamples/npdev-canary/Input/config.json");
        assertTrue(Files.isRegularFile(canary), "canary config not found at " + canary);
        JsonNode config = MAPPER.readTree(canary.toFile());
        assertDoesNotThrow(() -> ConfigSchemaValidator.verify(config, canary.toString()),
                "the T1-frozen canary must satisfy its own contract -- it did NOT until W6.1, which "
                + "is the whole reason this check exists");
    }

    @Test
    @DisplayName("an engine the platform does not have is refused, naming the field")
    void unknownProviderIsRefused() throws Exception {
        JsonNode config = MAPPER.readTree(minimalConfig().replace("\"h2-local\"", "\"sqlite\""));
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> ConfigSchemaValidator.verify(config, "test-config.json"));
        assertTrue(refusal.getMessage().contains("provider"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("config.schema.json"), refusal.getMessage());
    }

    @Test
    @DisplayName("a server engine with no host is refused -- the conditional half of the contract")
    void serverEngineWithoutHostIsRefused() throws Exception {
        // The reason the connection fields became CONDITIONAL rather than optional. Demanding them
        // from h2-local is what made the canary invalid; dropping them for everyone would accept a
        // postgres scenario that cannot connect, and fail at connect time instead of here.
        JsonNode config = MAPPER.readTree(minimalConfig().replace("\"h2-local\"", "\"postgres\""));
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> ConfigSchemaValidator.verify(config, "test-config.json"));
        assertTrue(refusal.getMessage().contains("host"), refusal.getMessage());
    }

    @Test
    @DisplayName("no config at all is not an error -- the generator can run on CLI arguments alone")
    void nullConfigIsAccepted() {
        assertDoesNotThrow(() -> ConfigSchemaValidator.verify(null, null));
    }

    private static String minimalConfig() {
        return """
                {
                  "configVersion": "1.0",
                  "scenario": { "name": "t", "outputRoot": "out" },
                  "generator": {
                    "failIfModelMissing": true, "failIfConfigMissing": true,
                    "cleanOutputBeforeGenerate": true, "emitPluginAssets": true,
                    "emitRuntimeAssets": true, "emitUiAssets": true
                  },
                  "bootstrap": { "root": "r", "mergeStrategy": "clean-copy" },
                  "artifact": { "root": "a", "generatedFolderName": "g", "libsFolderName": "l",
                                "metaFolderName": "m" },
                  "finalExec": { "root": "f", "deleteBeforeMount": true },
                  "database": { "provider": "h2-local", "database": "d", "resetMode": "reset" },
                  "runtime": { "springProfile": "dev", "serverPort": 8080, "javaArgs": [],
                               "gradleTask": "bootRun" }
                }
                """;
    }

    /** The repo root, identified by CONTENTS and never by directory name (REG-144). */
    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("NPDevContract"))
                    && Files.isDirectory(candidate.resolve("NPDevGenerator"))
                    && Files.isDirectory(candidate.resolve("NPDevKernel"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not identify the repo root by contents from " + Path.of("").toAbsolutePath());
    }
}
