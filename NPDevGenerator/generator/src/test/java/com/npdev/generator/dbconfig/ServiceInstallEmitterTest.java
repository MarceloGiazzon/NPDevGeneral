package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9.6: {@code npdev service install} -- supervision without Docker.
 *
 * <p>MON-17 (R9.4) promoted duplicate-PID-guarded/port-conflict-guarded {@code Start-App.ps1}/
 * {@code Stop-App.ps1} (+ POSIX twins) into {@link OperationalRunbookEmitter} for every generation
 * path. This pins the supervisor {@link OperationalRunbookEmitter} now wraps around them:
 * {@code Install-Service.ps1}/{@code Uninstall-Service.ps1} (a Windows Scheduled Task heartbeat
 * calling the already-guarded {@code Start-App.ps1} -- there is no way to register an arbitrary
 * script as a true SCM service without a third-party wrapper binary this platform does not bundle)
 * and {@code install-service.sh}/{@code uninstall-service.sh} (a REAL systemd unit wrapping {@code
 * run-final-app.sh}, {@code Restart=always}). The two platforms are deliberately NOT the same
 * mechanism -- see each script's own header comment.
 */
class ServiceInstallEmitterTest {

    private static Path writeInitStyleDefinition(Path parent, String appId) throws Exception {
        Path appDir = Files.createDirectories(parent.resolve(appId));
        Files.writeString(appDir.resolve("manifest.json"),
                "{\"id\": \"" + appId + "\", \"title\": \"" + appId + "\"}\n");
        Path definitionPath = appDir.resolve("db.definition.json");
        Files.writeString(definitionPath, """
                {
                  "database": { "engine": "H2Local", "username": "sa", "password": "",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
                }
                """);
        return definitionPath;
    }

    private static Path emit(Path tempDir, String appId) throws Exception {
        Path definitionPath = writeInitStyleDefinition(tempDir.resolve("src"), appId);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        Path appRoot = tempDir.resolve("FinalApp");
        Files.createDirectories(appRoot);
        Path opsRoot = new OperationalRunbookEmitter().emit(null, null, appRoot, plan);
        assertTrue(Files.exists(opsRoot), "emit() must return the _ops directory it wrote into");
        return opsRoot;
    }

    @Test
    void emitsAllFourServiceScripts(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir, "r96-service-test");

        assertTrue(Files.exists(opsRoot.resolve("Install-Service.ps1")));
        assertTrue(Files.exists(opsRoot.resolve("Uninstall-Service.ps1")));
        assertTrue(Files.exists(opsRoot.resolve("install-service.sh")));
        assertTrue(Files.exists(opsRoot.resolve("uninstall-service.sh")));
    }

    @Test
    void windowsInstallerIsIdempotentAndRefusesANameConflictRatherThanClobberingIt(@TempDir Path tempDir)
            throws Exception {
        Path opsRoot = emit(tempDir, "r96-service-test");
        String install = Files.readString(opsRoot.resolve("Install-Service.ps1"));

        assertTrue(install.contains("Get-ScheduledTask -TaskName $taskName"),
                "must check for an existing task before registering: " + install);
        assertTrue(install.contains("already installed for this app"),
                "re-running against the same app must be a documented no-op: " + install);
        assertTrue(install.contains("Refused:") && install.contains("DIFFERENT app"),
                "a name collision with a different app's launcher must be refused, not overwritten: " + install);
        assertTrue(install.contains("existing task action") && install.contains("this app's launcher"),
                "the refusal must NAME both sides of the conflict (MON-18/PACK-13 house style): " + install);
        assertTrue(install.contains("$DryRun") && install.contains("Nothing was installed."),
                "a dry-run/validate path must exist that makes zero changes: " + install);
        assertTrue(install.contains("Start-App.ps1"),
                "must supervise the EXISTING guarded launcher, not reimplement java-launching: " + install);
        assertFalse(install.contains("java -jar") && !install.contains("Start-App.ps1"),
                "must not itself invoke java -- that is Start-App.ps1's job: " + install);
        assertTrue(install.contains("SYSTEM") && install.contains("AtStartup"),
                "must start at boot with nobody logged in: " + install);
        assertTrue(install.contains("not a true SCM service") || install.contains("not appear in") ,
                "must state plainly that this is a Scheduled Task, not a real Windows service: " + install);
        assertTrue(install.contains("Administrator"),
                "a real install must require elevation, checked explicitly rather than failing opaquely: "
                        + install);
        assertFalse(install.contains("agent-proxy") || install.contains("api-key.env"),
                "must never touch secrets directly -- Start-App.ps1 already owns that: " + install);
    }

    @Test
    void windowsUninstallerIsIdempotentAndDoesNotClaimToStopTheApp(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir, "r96-service-test");
        String uninstall = Files.readString(opsRoot.resolve("Uninstall-Service.ps1"));

        assertTrue(uninstall.contains("Nothing to do"),
                "uninstalling twice (or never having installed) must be a clean no-op: " + uninstall);
        assertTrue(uninstall.contains("Unregister-ScheduledTask"), uninstall);
        assertTrue(uninstall.contains("did NOT stop the app itself") || uninstall.contains("Stop-App.ps1"),
                "must not silently imply the app was stopped too -- that is a separate operation: " + uninstall);
    }

    @Test
    void systemdInstallerBakesTheSanitizedAppIdAndUsesRestartAlwaysNotOnFailure(@TempDir Path tempDir)
            throws Exception {
        Path opsRoot = emit(tempDir, "r96-service-test");
        String install = Files.readString(opsRoot.resolve("install-service.sh"));

        assertTrue(install.startsWith("#!/bin/sh"), install);
        assertTrue(install.contains("UNIT_NAME=\"npdev-r96-service-test\""),
                "the sanitized appId must be baked as a literal token -- sh has no JSON parser: " + install);
        assertTrue(install.contains("ExecStart=/bin/sh $APP_ROOT/_ops/run-final-app.sh"),
                "must supervise the EXISTING foreground launcher, not reimplement java-launching: " + install);
        assertTrue(install.contains("Restart=always"),
                "must be Restart=always, not on-failure -- run-final-app.sh's tee-piped exit code does "
                        + "not reliably reflect a killed java process in POSIX sh: " + install);
        assertFalse(install.contains("Restart=on-failure"), install);
        assertTrue(install.contains("WantedBy=multi-user.target"), install);
        assertTrue(install.contains("# NPDEV_APP_ROOT=$APP_ROOT"),
                "must mark the unit with the app root it supervises, so a same-name conflict from a "
                        + "DIFFERENT app can be detected: " + install);
        assertTrue(install.contains("already installed for this app"), install);
        assertTrue(install.contains("Refused:") && install.contains("DIFFERENT app"), install);
        assertTrue(install.contains("--dry-run") && install.contains("Nothing was installed."), install);
        assertTrue(install.contains("systemctl not found"),
                "must degrade honestly on a non-systemd machine rather than crashing: " + install);
        assertTrue(install.contains("must run as root"),
                "a real install must require root, refused with a plain message: " + install);
        assertFalse(install.contains("agent-proxy") || install.contains("api-key.env"),
                "must never touch secrets directly -- run-final-app.sh already owns that: " + install);
    }

    @Test
    void systemdUninstallerBakesTheSanitizedAppIdAndIsIdempotent(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir, "r96-service-test");
        String uninstall = Files.readString(opsRoot.resolve("uninstall-service.sh"));

        assertTrue(uninstall.startsWith("#!/bin/sh"), uninstall);
        assertTrue(uninstall.contains("UNIT_NAME=\"npdev-r96-service-test\""), uninstall);
        assertTrue(uninstall.contains("Nothing to do"), uninstall);
        assertTrue(uninstall.contains("systemctl stop") && uninstall.contains("systemctl disable"), uninstall);
        assertTrue(uninstall.contains("must run as root"), uninstall);
    }

    @Test
    void twoDifferentAppsGetTwoDifferentSystemdUnitNames(@TempDir Path tempDir) throws Exception {
        // The identity a name collision is judged against: two distinct apps must never resolve to
        // the same unit/task name, or the refusal logic above would fire on every unrelated pair.
        Path opsRootA = emit(tempDir.resolve("a"), "r96-app-a");
        Path opsRootB = emit(tempDir.resolve("b"), "r96-app-b");

        String installA = Files.readString(opsRootA.resolve("install-service.sh"));
        String installB = Files.readString(opsRootB.resolve("install-service.sh"));

        assertTrue(installA.contains("UNIT_NAME=\"npdev-r96-app-a\""), installA);
        assertTrue(installB.contains("UNIT_NAME=\"npdev-r96-app-b\""), installB);
    }
}
