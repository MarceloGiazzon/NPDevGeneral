package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-11 (Session 3b): pins the OAuth secret transport contract in the emitted launchers after
 * REG-212 was implemented (2026-09-17) -- the launchers MATERIALIZE
 * {@code secrets/oauth-google.env} from the OS credential store via
 * {@code npdev-manager --get-secret} when the file is absent, otherwise they only load whatever is
 * present; the generator itself only ever emits the {@code .example} shape. If the transport
 * mechanism changes again, this test forces the emitted launchers and the example docs to move
 * together.
 */
class OperationalRunbookOauthEnvContractTest {

    private static final String FALSE_WRITER_CLAIM = "injected by the launcher from the OS credential store";
    private static final String STALE_GAP_MARKER = "automated keyring-to-file transport is not implemented yet";

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

    /** The bits every emitted launcher must carry: materialize-when-absent, then load. */
    private static void assertTransportContract(String launcher, String launcherName, boolean isPs1) {
        assertTrue(launcher.contains("secrets/oauth-google.env"), launcherName + " must target secrets/oauth-google.env");
        assertTrue(launcher.contains("--get-secret"), launcherName + " must read the keyring via --get-secret");
        assertTrue(launcher.contains("NPDEV_OAUTH_GOOGLE_KEYRING_PROFILE"),
                launcherName + " must honor NPDEV_OAUTH_GOOGLE_KEYRING_PROFILE");
        assertTrue(launcher.contains("oauth-google"),
                launcherName + " must default the keyring profile base to oauth-google");
        assertFalse(launcher.contains(FALSE_WRITER_CLAIM), launcherName + " must use the REG-212 wording, not the old claim");
        if (isPs1) {
            assertTrue(launcher.contains("-not (Test-Path -LiteralPath $oauthEnv)"),
                    launcherName + " must attempt materialization when the file is absent (REG-212)");
            assertTrue(launcher.contains("$oauthProfile.client-id") && launcher.contains("$oauthProfile.client-secret"),
                    launcherName + " must derive the client-id/client-secret profile pair from the base");
            assertTrue(launcher.contains("if (Test-Path -LiteralPath $oauthEnv)"),
                    launcherName + " must load the file when present");
        } else {
            assertTrue(launcher.contains("[ ! -f \"$oauth_env\" ]"),
                    launcherName + " must attempt materialization when the file is absent (REG-212)");
            assertTrue(launcher.contains("$oauth_base.client-id") && launcher.contains("$oauth_base.client-secret"),
                    launcherName + " must derive the client-id/client-secret profile pair from the base");
            assertTrue(launcher.contains("if [ -f \"$oauth_env\" ]"),
                    launcherName + " must load the file when present");
        }
    }

    @Test
    void emitsExampleAndMaterializingTransportContract(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir);

        Path example = opsRoot.getParent().resolve("secrets").resolve("oauth-google.env.example");
        assertTrue(Files.exists(example), "oauth-google.env.example must be emitted");
        String exampleText = Files.readString(example);
        assertTrue(exampleText.contains("--set-secret"), "example must document the keyring set path");
        assertTrue(exampleText.contains("NPDEV_OAUTH_GOOGLE_KEYRING_PROFILE"),
                "example must document the keyring profile base override");
        assertTrue(exampleText.contains("client-id") && exampleText.contains("client-secret"),
                "example must document the id/secret profile pair it materializes from");
        assertFalse(exampleText.contains(STALE_GAP_MARKER),
                "example must not still claim the transport is unimplemented (REG-212)");

        assertTransportContract(read(opsRoot, "Run-FinalApp.ps1"), "Run-FinalApp.ps1", true);
        assertTransportContract(read(opsRoot, "Start-App.ps1"), "Start-App.ps1", true);
    }

    @Test
    void posixTwinsCarryTheSameMaterializingContract(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir);

        assertTransportContract(read(opsRoot, "run-final-app.sh"), "run-final-app.sh", false);
        assertTransportContract(read(opsRoot, "start-app.sh"), "start-app.sh", false);
    }
}