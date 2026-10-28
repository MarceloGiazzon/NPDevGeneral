package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-38: an app started by a launcher that left no {@code _ops/app.pid} (another tool, an older
 * CLI) used to get "No app.pid; nothing to stop." from its own {@code Stop-App.ps1} while still
 * holding the port. The script now falls back to the port's listener -- but only one that is
 * provably this app, never a stranger that happens to hold the port.
 */
class StopAppScriptTest {

    private static String emitStopApp(Path tempDir) throws Exception {
        return Files.readString(emitOps(tempDir).resolve("Stop-App.ps1"));
    }

    private static Path emitOps(Path tempDir) throws Exception {
        Path appDir = Files.createDirectories(tempDir.resolve("src").resolve("run38-app"));
        Files.writeString(appDir.resolve("manifest.json"), "{\"id\": \"run38-app\", \"title\": \"run38-app\"}\n");
        Path definitionPath = appDir.resolve("db.definition.json");
        Files.writeString(definitionPath, """
                {
                  "database": { "engine": "H2Local", "username": "sa", "password": "",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
                }
                """);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        Path appRoot = Files.createDirectories(tempDir.resolve("FinalApp"));
        return new OperationalRunbookEmitter().emit(null, null, appRoot, plan);
    }

    @Test
    void withoutAPidFileFindsTheAppByItsCommandLineNotByThePlanPort(@TempDir Path tempDir) throws Exception {
        String stop = emitStopApp(tempDir);

        assertFalse(stop.contains("nothing to stop.'; exit 0"),
                "a missing app.pid must no longer end the script before the fallback runs: " + stop);
        assertTrue(stop.contains("function Get-NpdevAppRoot"),
                "the app root the command line is matched against must come from the shared resolver: " + stop);
        assertTrue(stop.contains("Name = 'java.exe' OR Name = 'javaw.exe'")
                        && stop.contains(".IndexOf($appRootMarker, [System.StringComparison]::OrdinalIgnoreCase)"),
                "must stop java processes whose command line names this app's root, whatever port they use "
                        + "(npdev run app --port overrides the plan's): " + stop);
    }

    @Test
    void aStrangerOnThePlanPortIsReportedAndLeftRunning(@TempDir Path tempDir) throws Exception {
        String stop = emitStopApp(tempDir);

        assertTrue(stop.contains("Get-NetTCPConnection -LocalPort ([int]$plan.serverPort) -State Listen"), stop);
        assertTrue(stop.contains("which is not this app; left running."),
                "a stranger on the port must be reported, never stopped: " + stop);
    }

    @Test
    void thePosixTwinHasTheSameFallback(@TempDir Path tempDir) throws Exception {
        String stop = Files.readString(emitOps(tempDir).resolve("stop-app.sh"));

        assertFalse(stop.contains("nothing to stop.'\n  exit 0"),
                "a missing app.pid must no longer end stop-app.sh before the fallback runs: " + stop);
        assertTrue(stop.contains("ps -eo pid=,args=") && stop.contains("index($0, root) > 0")
                        && stop.contains("index($0, \"org.h2.tools.Server\") == 0")
                        && stop.contains("APP_ROOT=$(cd \"$SCRIPT_DIR/..\" && pwd)"),
                "must stop java processes whose command line names this app's root: " + stop);
        assertTrue(stop.contains("npdev_plan_get() {")
                        && stop.contains("is held by a process that is not this app; left running."),
                "a stranger on the plan port must be reported, never stopped: " + stop);
    }
}
