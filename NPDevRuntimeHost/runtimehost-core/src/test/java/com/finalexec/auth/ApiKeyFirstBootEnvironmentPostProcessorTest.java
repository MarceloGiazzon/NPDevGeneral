package com.finalexec.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-1: a bare {@code java -jar} of an apikey-mode app in a non-prod profile must boot with a
 * provisioned key, using the SAME {@code secrets/api-key.env} file every launcher already writes;
 * and the cases that must stay fail-closed (prod, jwt, an explicitly supplied key) must not
 * provision anything.
 */
class ApiKeyFirstBootEnvironmentPostProcessorTest {

    private final ApiKeyFirstBootEnvironmentPostProcessor processor = new ApiKeyFirstBootEnvironmentPostProcessor();

    private static MockEnvironment environmentWithKeyFile(Path keyFile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.auth.api-key-file", keyFile.toString());
        return environment;
    }

    @Test
    @DisplayName("no key + dev profile: file created in the launcher format and the property is set")
    void generatesAKeyAndWritesTheLauncherFormat(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("secrets").resolve("api-key.env");
        MockEnvironment environment = environmentWithKeyFile(keyFile);
        environment.setActiveProfiles("dev");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertTrue(Files.isRegularFile(keyFile));
        String content = Files.readString(keyFile, StandardCharsets.UTF_8);
        assertTrue(content.matches("^NPDEV_AUTH_API_KEYS=[0-9a-f]{64}=dev:developer:admin$"), content);
        assertEquals(content.substring("NPDEV_AUTH_API_KEYS=".length()),
                environment.getProperty("npdev.auth.api-keys"));
    }

    @Test
    @DisplayName("a second boot reuses the same key")
    void secondBootReusesTheKey(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("secrets").resolve("api-key.env");
        MockEnvironment first = environmentWithKeyFile(keyFile);
        processor.postProcessEnvironment(first, new SpringApplication());
        String firstMapping = first.getProperty("npdev.auth.api-keys");

        MockEnvironment second = environmentWithKeyFile(keyFile);
        processor.postProcessEnvironment(second, new SpringApplication());

        assertEquals(firstMapping, second.getProperty("npdev.auth.api-keys"));
        assertEquals("NPDEV_AUTH_API_KEYS=" + firstMapping, Files.readString(keyFile, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an existing launcher-written file with a BOM is reused, never overwritten")
    void existingBomPrefixedFileIsReused(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("secrets").resolve("api-key.env");
        Files.createDirectories(keyFile.getParent());
        String original = "﻿NPDEV_AUTH_API_KEYS=abc123=dev:developer:admin\r\n";
        Files.writeString(keyFile, original, StandardCharsets.UTF_8);
        MockEnvironment environment = environmentWithKeyFile(keyFile);

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("abc123=dev:developer:admin", environment.getProperty("npdev.auth.api-keys"));
        assertEquals(original, Files.readString(keyFile, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an already-configured key mapping means no file is created")
    void configuredKeyIsANoOp(@TempDir Path tempDir) {
        Path keyFile = tempDir.resolve("secrets").resolve("api-key.env");
        MockEnvironment environment = environmentWithKeyFile(keyFile);
        environment.setProperty("npdev.auth.api-keys", "mine=ops:operator:admin");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertFalse(Files.exists(keyFile));
        assertEquals("mine=ops:operator:admin", environment.getProperty("npdev.auth.api-keys"));
    }

    @Test
    @DisplayName("the prod profile stays fail-closed: nothing is provisioned")
    void prodProfileIsANoOp(@TempDir Path tempDir) {
        Path keyFile = tempDir.resolve("secrets").resolve("api-key.env");
        MockEnvironment environment = environmentWithKeyFile(keyFile);
        environment.setActiveProfiles("prod");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertFalse(Files.exists(keyFile));
        assertEquals(null, environment.getProperty("npdev.auth.api-keys"));
    }

    @Test
    @DisplayName("jwt mode never provisions an API key")
    void jwtModeIsANoOp(@TempDir Path tempDir) {
        Path keyFile = tempDir.resolve("secrets").resolve("api-key.env");
        MockEnvironment environment = environmentWithKeyFile(keyFile);
        environment.setProperty("npdev.auth.mode", "jwt");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertFalse(Files.exists(keyFile));
        assertEquals(null, environment.getProperty("npdev.auth.api-keys"));
    }
}
