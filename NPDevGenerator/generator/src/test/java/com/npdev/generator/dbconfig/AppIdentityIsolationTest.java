package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QUAL-3: two apps scaffolded into ONE folder must not become one database.
 *
 * <p>{@code resolveAppId} falls back to walking two directory levels up from {@code
 * db.definition.json}. That is correct for the corpus layouts ({@code <App>/definition/...} and
 * {@code <App>/Input/...}) and wrong for {@code npdev init}, which writes the definition directly
 * into the app directory -- so two levels up is {@code Apps}, the PARENT FOLDER shared by every app
 * in it.
 *
 * <p>Measured before the fix, with two real apps generated into one folder: both resolved to
 * {@code appId=qual3}, and therefore to {@code containerName=npdev-qual3} and data root
 * {@code Build/databases/qual3}. They were not two apps sharing a toolbox -- they were one database
 * with two front doors, and resetting either destroyed the other's data. The acknowledgement token
 * does not help: the user types it correctly, for the app they intend, and different data is
 * destroyed. A confirmation cannot save you from an operation aimed at the wrong target.
 *
 * <p><b>Fixed by DECLARING identity, not by inferring it better.</b> Keying on the directory name
 * ("if it is not called {@code definition}, this directory is the app") was tried and measured to
 * be worse: 25 corpus definitions live in a directory called {@code Input} with no manifest, and
 * that rule collapsed all 25 onto {@code appId=Input} -- a wider collision than the one being
 * fixed. Path shape cannot distinguish an app directory from a wrapper directory, so {@code npdev
 * init} now writes a {@code manifest.json} and the loader reads it.
 *
 * <p>Two apps in one folder is not exotic. It is what evaluating the product looks like.
 */
class AppIdentityIsolationTest {

    /** THE regression: distinct apps in one parent folder must get distinct identities. */
    @Test
    void twoAppsInOneFolderGetDistinctIdentities(@TempDir Path tempDir) throws Exception {
        Path a = writeInitStyleDefinition(tempDir, "app-a");
        Path b = writeInitStyleDefinition(tempDir, "app-b");

        GeneratedDatabasePlan planA = new UserDatabaseDefinitionLoader().load(a, null);
        GeneratedDatabasePlan planB = new UserDatabaseDefinitionLoader().load(b, null);

        assertEquals("app-a", planA.appId());
        assertEquals("app-b", planB.appId());
        assertNotEquals(planA.appId(), planB.appId(),
                "two apps in one folder must not share an appId -- containerName and the data root "
                        + "are both derived from it");
        assertNotEquals(planA.resolvedDataRoot(), planB.resolvedDataRoot(),
                "distinct apps must not share a data root; resetting one would delete the other's data");
    }

    /**
     * PORT-1 changed what this test is really asserting, so it says so out loud rather than leaving
     * the next reader to assume the old mechanism still holds.
     *
     * <p>The data root used to be {@code <workspace>/Build/databases/<appId>} -- one shared
     * directory for every app on the machine, where two apps colliding on {@code appId} genuinely
     * WAS one database with two front doors. It is now app-relative ({@code data}, or
     * {@code data/<generated name>}), resolved against each app's own FinalApp directory. Two apps
     * that both resolve to the string {@code data} therefore still have two separate databases,
     * because their FinalApp directories are different directories.
     *
     * <p>That makes the isolation structural rather than derived, which is stronger -- but it also
     * means the string comparison above would pass vacuously for any app that declares an explicit
     * {@code databaseName}. This test pins the property that still carries weight there: the appId,
     * from which the container name is built, and which is what {@code npdev db status --app} keys
     * on.
     */
    @Test
    void anExplicitDatabaseNameGivesTwoAppsTheSameRelativeRootAndStillNotTheSameDatabase(
            @TempDir Path tempDir) throws Exception {
        Path a = writeInitStyleDefinition(tempDir, "app-a", "shared_name");
        Path b = writeInitStyleDefinition(tempDir, "app-b", "shared_name");

        GeneratedDatabasePlan planA = new UserDatabaseDefinitionLoader().load(a, null);
        GeneratedDatabasePlan planB = new UserDatabaseDefinitionLoader().load(b, null);

        assertEquals("data", planA.resolvedDataRoot(),
                "PORT-1: the data root is app-relative, never this machine's absolute path");
        assertEquals(planA.resolvedDataRoot(), planB.resolvedDataRoot(),
                "and identical as a STRING for two apps naming the same database -- which is safe "
                        + "only because each is resolved against its own FinalApp directory");
        assertNotEquals(planA.appId(), planB.appId(),
                "the identity that still has to differ: containerName and `npdev db status --app` "
                        + "are both derived from appId");
    }

    /** PORT-1: no emitted path may carry the generating machine's layout. */
    @Test
    void theDataRootIsRelativeAndNamesNoDrive(@TempDir Path tempDir) throws Exception {
        Path definition = writeInitStyleDefinition(tempDir, "portable-app", "");

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definition, null);

        assertFalse(Path.of(plan.resolvedDataRoot()).isAbsolute(),
                "an absolute data root is baked into spring.datasource.url and resolved at BOOT, so "
                        + "a stranger's app opens its database on a drive they may not have: "
                        + plan.resolvedDataRoot());
        assertTrue(plan.resolvedDataRoot().startsWith("data"),
                "the root is <FinalApp>/data: " + plan.resolvedDataRoot());
        assertTrue(plan.jdbcUrl().startsWith("jdbc:h2:file:./data/"),
                "the H2 URL must be app-relative too -- it is the one value Spring resolves at "
                        + "boot, and the only one of the six that stops an app working: "
                        + plan.jdbcUrl());
        assertFalse(plan.jdbcUrl().contains(tempDir.toString().replace('\\', '/')),
                "the URL must not name the generating machine's directory: " + plan.jdbcUrl());
    }

    /**
     * The AppGen layout must keep behaving exactly as before: there the definition genuinely lives
     * one level below the app, and the app is the directory holding {@code definition/}.
     */
    @Test
    void appGenLayoutStillResolvesToTheAppDirectoryNotTheDefinitionDirectory(@TempDir Path tempDir) throws Exception {
        Path appDir = Files.createDirectories(tempDir.resolve("WmsOffice"));
        Path definitionDir = Files.createDirectories(appDir.resolve("definition"));
        Path definitionPath = definitionDir.resolve("db.definition.json");
        Files.writeString(definitionPath, h2LocalDefinition());

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);

        assertEquals("WmsOffice".toLowerCase(), plan.appId().toLowerCase(),
                "the AppGen layout's appId is the directory holding definition/, not 'definition'");
    }

    /**
     * Exactly what `npdev init <dir>` writes: db.definition.json straight into the app directory,
     * plus the manifest.json that names the app. The manifest is the load-bearing part -- drop it
     * and both apps fall back to the shared parent folder's name, which is the defect.
     */
    private static Path writeInitStyleDefinition(Path parent, String appName) throws Exception {
        return writeInitStyleDefinition(parent, appName, "");
    }

    private static Path writeInitStyleDefinition(Path parent, String appName, String databaseName)
            throws Exception {
        Path appDir = Files.createDirectories(parent.resolve(appName));
        Files.writeString(appDir.resolve("manifest.json"),
                "{\"id\": \"" + appName + "\", \"title\": \"" + appName + "\"}\n");
        Path definitionPath = appDir.resolve("db.definition.json");
        Files.writeString(definitionPath, databaseName.isBlank()
                ? h2LocalDefinition()
                : h2LocalDefinition().replace("\"engine\": \"H2Local\",",
                        "\"engine\": \"H2Local\", \"databaseName\": \"" + databaseName + "\","));
        return definitionPath;
    }

    /**
     * The failure mode, pinned so the fix cannot quietly regress: WITHOUT a manifest the two apps
     * collapse onto the parent folder's identity. This asserts the hazard is real rather than
     * theoretical, which is why `npdev init` writing the manifest matters.
     */
    @Test
    void withoutAManifestTwoAppsInOneFolderStillCollide(@TempDir Path tempDir) throws Exception {
        Path a = Files.createDirectories(tempDir.resolve("app-a")).resolve("db.definition.json");
        Files.writeString(a, h2LocalDefinition());
        Path b = Files.createDirectories(tempDir.resolve("app-b")).resolve("db.definition.json");
        Files.writeString(b, h2LocalDefinition());

        GeneratedDatabasePlan planA = new UserDatabaseDefinitionLoader().load(a, null);
        GeneratedDatabasePlan planB = new UserDatabaseDefinitionLoader().load(b, null);

        assertEquals(planA.appId(), planB.appId(),
                "documented hazard: with no manifest, identity falls back to the shared parent "
                        + "folder -- which is why `npdev init` writes one");
    }

    private static String h2LocalDefinition() {
        return """
                {
                  "database": { "engine": "H2Local", "username": "sa", "password": "",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
                }
                """;
    }
}
