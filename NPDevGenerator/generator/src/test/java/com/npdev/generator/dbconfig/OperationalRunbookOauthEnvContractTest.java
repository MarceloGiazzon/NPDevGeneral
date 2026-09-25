package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-11 (Session 3b): pins the OAuth secret transport contract in the emitted launchers after
 * REG-212 option (b) was implemented (2026-09-18) -- the launchers read the Google OAuth
 * client id/secret directly from the OS credential store via
 * {@code npdev-manager --get-secret} and inject them into the process environment.
 * No file is persisted anywhere, in compliance with SEC-11 decision 3 ("never in .env").
 * If the transport mechanism changes again, this test forces the emitted launchers and the
 * example docs to move together.
 */
class OperationalRunbookOauthEnvContractTest {

    private static final String OBSOLETE_MATERIALIZE_CLAIM = "Materialized oauth-google.env";
    private static final String OBSOLETE_ENV_FILE_REFERENCE = "secrets/oauth-google.env";

    private static Path emit(@TempDir Path tempDir) throws Exception {
        Path definitionPath = tempDir.resolve("src").resolve("db.definition.json");
        Files.createDirectories(definitionPath.getParent());
        Files.writeString(definitionPath, """
                {
                  "database": { "engine": "H2Local", "databaseName": "canary", "username": "sa",
                                 "password": "", "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly" }
                }
                """);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        Path appRoot = tempDir.resolve("FinalApp");
        Files.createDirectories(appRoot);
        return new OperationalRunbookEmitter().emit(null, null, appRoot, plan);
    }

    private static String read(Path root, String relative) throws Exception {
        return Files.readString(root.resolve(relative));
    }

    /** The bits every emitted launcher must carry: direct keyring injection, no file. */
    private static void assertTransportContract(String launcher, String launcherName, boolean isPs1) {
        // Must read from keyring directly.
        assertTrue(launcher.contains("--get-secret"), launcherName + " must read the keyring via --get-secret");
        assertTrue(launcher.contains("NPDEV_OAUTH_GOOGLE_KEYRING_PROFILE"),
                launcherName + " must honor NPDEV_OAUTH_GOOGLE_KEYRING_PROFILE");
        assertTrue(launcher.contains("oauth-google"),
                launcherName + " must default the keyring profile base to oauth-google");
        // Must inject directly into process env.
        if (isPs1) {
            assertTrue(launcher.contains("Set-Item -Path env:NPDEV_OAUTH_GOOGLE_CLIENT_ID"),
                    launcherName + " must inject client id into process env");
            assertTrue(launcher.contains("Set-Item -Path env:NPDEV_OAUTH_GOOGLE_CLIENT_SECRET"),
                    launcherName + " must inject client secret into process env");
        } else {
            assertTrue(launcher.contains("export NPDEV_OAUTH_GOOGLE_CLIENT_ID"),
                    launcherName + " must export client id into process env");
            assertTrue(launcher.contains("export NPDEV_OAUTH_GOOGLE_CLIENT_SECRET"),
                    launcherName + " must export client secret into process env");
        }
        // Must NOT write to or load from a persisted file.
        assertFalse(launcher.contains(OBSOLETE_MATERIALIZE_CLAIM),
                launcherName + " must not materialize an env file (option b: keyring only)");
        assertFalse(launcher.contains(OBSOLETE_ENV_FILE_REFERENCE),
                launcherName + " must not reference a persisted env file (option b: keyring only)");
    }

    /**
     * Wave 3 (NPDEV_FEATURE_PLAN_2026-09-24): the GitHub twin of {@link #assertTransportContract}.
     */
    private static void assertGithubTransportContract(String launcher, String launcherName, boolean isPs1) {
        assertTrue(launcher.contains("--get-secret"), launcherName + " must read the keyring via --get-secret");
        assertTrue(launcher.contains("NPDEV_OAUTH_GITHUB_KEYRING_PROFILE"),
                launcherName + " must honor NPDEV_OAUTH_GITHUB_KEYRING_PROFILE");
        assertTrue(launcher.contains("oauth-github"),
                launcherName + " must default the keyring profile base to oauth-github");
        if (isPs1) {
            assertTrue(launcher.contains("Set-Item -Path env:NPDEV_OAUTH_GITHUB_CLIENT_ID"),
                    launcherName + " must inject client id into process env");
            assertTrue(launcher.contains("Set-Item -Path env:NPDEV_OAUTH_GITHUB_CLIENT_SECRET"),
                    launcherName + " must inject client secret into process env");
        } else {
            assertTrue(launcher.contains("export NPDEV_OAUTH_GITHUB_CLIENT_ID"),
                    launcherName + " must export client id into process env");
            assertTrue(launcher.contains("export NPDEV_OAUTH_GITHUB_CLIENT_SECRET"),
                    launcherName + " must export client secret into process env");
        }
    }

    @Test
    void emitsExampleAndKeyringTransportContract(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir);

        Path example = opsRoot.getParent().resolve("secrets").resolve("oauth-google.env.example");
        assertTrue(Files.exists(example), "oauth-google.env.example must be emitted");
        String exampleText = Files.readString(example);
        assertTrue(exampleText.contains("--set-secret"), "example must document the keyring set path");
        assertTrue(exampleText.contains("NPDEV_OAUTH_GOOGLE_KEYRING_PROFILE"),
                "example must document the keyring profile base override");
        assertTrue(exampleText.contains("client-id") && exampleText.contains("client-secret"),
                "example must document the id/secret profile pair");
        assertFalse(exampleText.contains("Copy this file to"),
                "example must not tell operators to copy a file (option b: keyring only)");
        assertFalse(exampleText.contains(OBSOLETE_MATERIALIZE_CLAIM),
                "example must not reference obsolete materialize claim");

        Path githubExample = opsRoot.getParent().resolve("secrets").resolve("oauth-github.env.example");
        assertTrue(Files.exists(githubExample), "oauth-github.env.example must be emitted");
        String githubExampleText = Files.readString(githubExample);
        assertTrue(githubExampleText.contains("--set-secret"), "GitHub example must document the keyring set path");
        assertTrue(githubExampleText.contains("NPDEV_OAUTH_GITHUB_KEYRING_PROFILE"),
                "GitHub example must document the keyring profile base override");

        assertTransportContract(read(opsRoot, "Run-FinalApp.ps1"), "Run-FinalApp.ps1", true);
        assertTransportContract(read(opsRoot, "Start-App.ps1"), "Start-App.ps1", true);
        assertGithubTransportContract(read(opsRoot, "Run-FinalApp.ps1"), "Run-FinalApp.ps1", true);
        assertGithubTransportContract(read(opsRoot, "Start-App.ps1"), "Start-App.ps1", true);
    }

    @Test
    void posixTwinsCarryTheSameKeyringContract(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir);

        assertTransportContract(read(opsRoot, "run-final-app.sh"), "run-final-app.sh", false);
        assertTransportContract(read(opsRoot, "start-app.sh"), "start-app.sh", false);
        assertGithubTransportContract(read(opsRoot, "run-final-app.sh"), "run-final-app.sh", false);
        assertGithubTransportContract(read(opsRoot, "start-app.sh"), "start-app.sh", false);
    }
}