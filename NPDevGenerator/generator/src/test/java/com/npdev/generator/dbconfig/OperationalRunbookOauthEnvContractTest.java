package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-11 (Session 3b): pins the TRUE OAuth secret transport contract in the emitted launchers
 * after REG-212 -- the launchers only CONSUME {@code secrets/oauth-google.env} when present; they
 * never read the OS credential store themselves, and the generator only ever emits the
 * {@code .example} shape. Guards against the false "the launcher writes this file from it at boot"
 * claim (fixed 2026-09-17): if someone implements automated keyring-to-file transport, this test
 * forces the docs and the loader claims to move together.
 */
class OperationalRunbookOauthEnvContractTest {

    private static final String FALSE_WRITER_CLAIM = "injected by the launcher from the OS credential store";
    private static final String FALSE_EXAMPLE_CLAIM = "the launcher writes this file from it at boot";
    private static final String TRUTH_EXAMPLE_MARKER = "automated keyring-to-file transport is not implemented yet";

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

    @Test
    void emitsExampleAndTruthfulTransportContract(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir);

        Path example = opsRoot.getParent().resolve("secrets").resolve("oauth-google.env.example");
        assertTrue(Files.exists(example), "oauth-google.env.example must be emitted");
        String exampleText = Files.readString(example);
        assertTrue(exampleText.contains("--set-secret"), "example must document the keyring set path");
        assertTrue(exampleText.contains(TRUTH_EXAMPLE_MARKER),
                "example must state the transport is operator-provisioned, not launcher-written");
        assertFalse(exampleText.contains(FALSE_EXAMPLE_CLAIM),
                "example must not claim the launcher writes the file at boot (REG-212)");

        String launcher = read(opsRoot, "Run-FinalApp.ps1");
        assertTrue(launcher.contains("Join-Path $appRoot 'secrets/oauth-google.env'"),
                "launcher must load secrets/oauth-google.env");
        assertTrue(launcher.contains("if (Test-Path -LiteralPath $oauthEnv)"),
                "launcher must only consume the file when present");
        assertFalse(launcher.contains(FALSE_WRITER_CLAIM),
                "launcher must not claim it injects from the OS credential store (REG-212)");
    }

    @Test
    void posixTwinCarriesTheSameTruthfulContract(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir);

        String launcher = read(opsRoot, "run-final-app.sh");
        assertTrue(launcher.contains("oauth_env=\"$app_root/secrets/oauth-google.env\""),
                "POSIX launcher must load secrets/oauth-google.env");
        assertTrue(launcher.contains("if [ -f \"$oauth_env\" ]"),
                "POSIX launcher must only consume the file when present");
        assertFalse(launcher.contains(FALSE_WRITER_CLAIM),
                "POSIX launcher must not claim it injects from the OS credential store (REG-212)");
    }
}