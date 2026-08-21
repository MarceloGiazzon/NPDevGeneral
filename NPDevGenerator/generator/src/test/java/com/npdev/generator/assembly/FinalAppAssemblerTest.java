package com.npdev.generator.assembly;

import com.npdev.generator.packs.PackAbiIncompatibleException;
import com.npdev.generator.packs.SealedPackJarBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalAppAssemblerTest {

    @Test
    void assemblesRunnableAppFromHostTemplateGeneratedArtifactAndMigrations() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path migrations = workspace.resolve("db-history").resolve("src/main/resources/db/migration");
        Path snapshots = workspace.resolve("db-history").resolve("src/main/resources/db/schema-snapshots");
        Path finalApp = workspace.resolve("FinalExec");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(host.resolve("settings.gradle"), "rootProject.name = 'FinalExec'\n");
        write(host.resolve("gradle.properties"), "org.gradle.jvmargs=-Xms1g -Xmx6g\norg.gradle.daemon=true\n");
        write(host.resolve("README.md"),
                "# NPDevRuntimeHost\n\nProvide the template runtime shell that hosts assembled NPDev applications.\n");
        write(host.resolve("PROJECT_DIGEST.md"), "# NPDevRuntimeHost Project Digest\n\nMaintainer-only.\n");
        write(host.resolve("MIGRATION_DIGEST.md"), "# RuntimeHost: Base Template Migration Digest\n\nMaintainer-only.\n");
        write(host.resolve("NO_BUILD_ARTIFACTS.policy"), "NO BUILD ARTIFACTS IN THIS SOURCE TREE\n");
        write(host.resolve("src/main/java/com/finalexec/FinalExecApplication.java"), "package com.finalexec;\n");
        write(host.resolve("src/main/java/com/finalexec/api/RuntimeMetadataController.java"), "package com.finalexec.api;\n");
        write(host.resolve("src/main/java/com/finalexec/api/internal/TemplateLibraryManagementController.java"), "package com.finalexec.api.internal;\n");
        write(host.resolve("src/main/java/com/finalexec/api/experimental/FlowBuilderController.java"), "package com.finalexec.api.experimental;\n");
        write(host.resolve("src/main/java/com/finalexec/npdev/service/internal/ModelSyncStatusService.java"), "package com.finalexec.npdev.service.internal;\n");
        write(host.resolve("src/main/java/com/finalexec/npdev/service/experimental/FlowBuilderService.java"), "package com.finalexec.npdev.service.experimental;\n");
        write(host.resolve("src/main/resources/db/migration/V5001__runtime.sql"), "select 1;\n");
        write(host.resolve("libs/kernel-0.1.0.jar"), "jar");
        // BT-1: the app-independent runtimehost-core module lives nested under the host root but
        // must never be copied into a generated app -- it ships as a precompiled jar dependency
        // instead (see EXCLUDED_DIRECTORY_NAMES's own comment).
        write(host.resolve("runtimehost-core/build.gradle"), "plugins { id 'java-library' }\n");
        write(host.resolve("runtimehost-core/src/main/java/com/finalexec/api/RuntimeSchedulesController.java"), "package com.finalexec.api;\n");
        write(host.resolve(".gradle/cache.bin"), "cache");
        write(host.resolve(".idea/workspace.xml"), "idea");
        write(host.resolve("build/classes/Main.class"), "class");
        write(host.resolve("npdev-generated/src/main/java/Old.java"), "old generated");
        write(host.resolve("npdev-meta/export-manifest.txt"), "old meta");
        write(host.resolve("npdev-build-info.properties"), "old build info");

        write(artifact.resolve("src/main/java/com/npdev/generated/entities/User.java"), "package com.npdev.generated.entities;\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");
        write(artifact.resolve("src/main/resources/npdev/support/generated-folder.signature.properties"),
                "contract=npdev-generated-folder-signature-v1\n");
        write(artifact.resolve("src/main/resources/db/migration/R__should_not_mount_here.sql"), "select 2;\n");
        write(migrations.resolve("R__npdev_schema.sql"), "select 3;\n");
        write(migrations.resolve("V5013__legacy_sample.sql"), "select 4;\n");
        write(snapshots.resolve("latest-storage-schema.json"), "{\"tables\":[]}\n");

        FinalAppAssembler.AssemblyResult result = new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        host,
                        artifact,
                        finalApp,
                        migrations,
                        "npdev-generated",
                        "npdev-meta",
                        true,
                        17,
                        null
                )
        );

        assertEquals(finalApp.toAbsolutePath().normalize(), result.finalAppRoot());
        assertEquals(finalApp.resolve("npdev-generated").toAbsolutePath().normalize(), result.generatedMount());
        assertTrue(Files.exists(finalApp.resolve("build.gradle")));
        assertFalse(Files.exists(finalApp.resolve("build.gradle.template")));
        assertTrue(Files.exists(finalApp.resolve("settings.gradle")));
        assertTrue(Files.exists(finalApp.resolve("src/main/java/com/finalexec/FinalExecApplication.java")));
        assertTrue(Files.exists(finalApp.resolve("src/main/java/com/finalexec/api/RuntimeMetadataController.java")));
        assertTrue(Files.exists(finalApp.resolve("src/main/java/com/finalexec/api/internal/TemplateLibraryManagementController.java")));
        assertFalse(Files.exists(finalApp.resolve("src/main/java/com/finalexec/api/experimental/FlowBuilderController.java")));
        assertTrue(Files.exists(finalApp.resolve("src/main/java/com/finalexec/npdev/service/internal/ModelSyncStatusService.java")));
        assertFalse(Files.exists(finalApp.resolve("src/main/java/com/finalexec/npdev/service/experimental/FlowBuilderService.java")));
        assertFalse(Files.exists(finalApp.resolve("libs/kernel-0.1.0.jar")));
        assertFalse(Files.exists(finalApp.resolve("runtimehost-core/build.gradle")));
        assertFalse(Files.exists(finalApp.resolve("runtimehost-core/src/main/java/com/finalexec/api/RuntimeSchedulesController.java")));
        assertTrue(Files.exists(finalApp.resolve("npdev-generated/src/main/java/com/npdev/generated/entities/User.java")));
        assertTrue(Files.exists(finalApp.resolve("npdev-generated/src/main/resources/npdev/compiled-model.json")));
        assertTrue(Files.exists(finalApp.resolve("npdev-generated/src/main/resources/npdev/support/generated-folder.signature.properties")));
        assertTrue(Files.exists(finalApp.resolve("src/main/resources/db/migration/V5001__runtime.sql")));
        assertFalse(Files.exists(finalApp.resolve("src/main/resources/db/migration/R__npdev_schema.sql")));
        assertFalse(Files.exists(finalApp.resolve("src/main/resources/npdev/model-diff-baseline.json")));
        assertFalse(Files.exists(finalApp.resolve("gradle/wrapper/gradle-wrapper.jar")));
        assertFalse(Files.exists(finalApp.resolve("gradlew.bat")));
        Path schemaRealizationManifest = finalApp.resolve("src/main/resources/npdev/support/schema-realization.manifest.json");
        assertTrue(Files.exists(schemaRealizationManifest));
        String schemaRealizationManifestJson = Files.readString(schemaRealizationManifest);
        assertTrue(schemaRealizationManifestJson.contains("\"deliveryMode\" : \"recreate-style-app\""));
        assertTrue(schemaRealizationManifestJson.contains("\"schemaRealizationEnabled\" : true"));
        assertTrue(schemaRealizationManifestJson.contains("\"upgradeManagementSupported\" : false"));

        assertFalse(Files.exists(finalApp.resolve(".gradle/cache.bin")));
        assertFalse(Files.exists(finalApp.resolve(".idea/workspace.xml")));
        assertFalse(Files.exists(finalApp.resolve("build/classes/Main.class")));
        assertFalse(Files.exists(finalApp.resolve("npdev-meta/export-manifest.txt")));
        assertFalse(Files.exists(finalApp.resolve("npdev-build-info.properties")));
        assertFalse(Files.exists(finalApp.resolve("src/main/resources/db/migration/V5013__legacy_sample.sql")));
        assertFalse(Files.exists(finalApp.resolve("npdev-generated/src/main/resources/db/migration/R__should_not_mount_here.sql")));

        // F2 (FIRST_IMPRESSION_SPEC.md I3): the assembled app gets its OWN README, derived from the
        // compiled model, not NPDevRuntimeHost's maintainer-facing one -- and the maintainer-only
        // digests/policy file that used to ride along in the bulk copy are excluded entirely.
        Path readme = finalApp.resolve("README.md");
        assertTrue(Files.exists(readme));
        String readmeContent = Files.readString(readme);
        assertFalse(readmeContent.contains("Provide the template runtime shell"));
        assertTrue(readmeContent.contains("demo.sample"));
        assertTrue(readmeContent.contains("1.0"));
        // T1 (Handover Hardening Plan, 2026-08-15 Step 2): writeAppReadme was referenced only by
        // FinalAppAssembler itself -- nothing exercised the emitted README before this test. It used
        // to tell every reader to run `java -jar ... --spring.profiles.active=dev`, which never calls
        // Ensure-NpdevApiKey, leaving application-dev.yml's published api-dev/dev-key ADMIN pair live.
        assertFalse(readmeContent.contains("api-dev"), "emitted README must never publish the api-dev credential");
        assertFalse(readmeContent.contains("dev-key"), "emitted README must never publish the dev-key credential");
        assertFalse(readmeContent.contains("--spring.profiles.active=dev"),
                "emitted README must never show an un-keyed dev boot -- dev now fails closed with no key supplied");
        assertTrue(readmeContent.contains("_ops/Run-FinalApp.ps1"),
                "emitted README must point at the launcher that provisions a real admin API key");
        assertTrue(readmeContent.contains("_ops/run-final-app.sh"),
                "emitted README must offer the POSIX launcher twin too");
        assertFalse(Files.exists(finalApp.resolve("PROJECT_DIGEST.md")));
        assertFalse(Files.exists(finalApp.resolve("MIGRATION_DIGEST.md")));
        assertFalse(Files.exists(finalApp.resolve("NO_BUILD_ARTIFACTS.policy")));

        // REG-128 (FIRST_IMPRESSION_PLAN.md I8): the assembled app's own gradle.properties gets a
        // resolved npdevRuntimeHostLibsDir default APPENDED (original JVM/perf settings preserved,
        // not overwritten), so `./gradlew bootJar` finds the platform jars even when the app lives
        // nowhere near the source repo -- the harness's real-world case.
        Path gradleProperties = finalApp.resolve("gradle.properties");
        assertTrue(Files.exists(gradleProperties));
        String gradlePropertiesContent = Files.readString(gradleProperties);
        assertTrue(gradlePropertiesContent.contains("org.gradle.jvmargs=-Xms1g -Xmx6g"));
        assertTrue(gradlePropertiesContent.contains("npdevRuntimeHostLibsDir="));
        assertTrue(gradlePropertiesContent.contains("runtimehost-libs"));
        assertFalse(gradlePropertiesContent.contains("\\"), "path must use forward slashes -- \\ is a .properties escape character");

        // deps-and-java/PLAN.md W1.4: same append-only convention as npdevRuntimeHostLibsDir above,
        // for the app's own Gradle toolchain level.
        assertTrue(gradlePropertiesContent.contains("npdevAppJavaVersion=17"));
    }

    @Test
    void appendsTheRequestedJavaVersionNotAlwaysTheDefault() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-javaversion-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path finalApp = workspace.resolve("FinalExec");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");

        new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        host, artifact, finalApp, null, "npdev-generated", "npdev-meta", false, 21, null
                )
        );

        String gradlePropertiesContent = Files.readString(finalApp.resolve("gradle.properties"));
        assertTrue(gradlePropertiesContent.contains("npdevAppJavaVersion=21"));
    }

    /**
     * The wipe spares exactly the three directories regeneration cannot reproduce, and nothing else.
     *
     * <p>Two of the three were unproven before this test. {@code data} was PORT-1's and worked;
     * {@code logs} was on {@code Build-NpdevApp.ps1}'s spare list from MONITOR_PLAN D10 but NOT on
     * this class's, so the PowerShell layer spared it and this layer -- running second, in the same
     * build -- deleted it again, silently. {@code secrets} is new. The `stale.txt` assertion is the
     * other half of the claim: a spare list that spared everything would pass the first three
     * assertions and be useless.
     */
    @Test
    void regenerationSparesDataLogsAndSecretsAndDeletesEverythingElse() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-preserve-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path finalApp = workspace.resolve("FinalExec");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");

        // Stand in for a previously generated app that has been RUN: it has a database, a log
        // archive, an operator-written provider key, and stale emitted output.
        write(finalApp.resolve("data/npdev-app.mv.db"), "database bytes");
        write(finalApp.resolve("logs/run-2026-08-12.log"), "previous run stdout");
        write(finalApp.resolve("secrets/agent-proxy.env"), "NPDEV_EXTERNALAI_ANTHROPIC_API_KEY=sk-ant-test\n");
        write(finalApp.resolve("stale.txt"), "emitted by the previous generation");

        new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        host, artifact, finalApp, null, "npdev-generated", "npdev-meta", true, 17, null
                )
        );

        assertEquals("database bytes", Files.readString(finalApp.resolve("data/npdev-app.mv.db")));
        assertEquals("previous run stdout", Files.readString(finalApp.resolve("logs/run-2026-08-12.log")));
        assertEquals("NPDEV_EXTERNALAI_ANTHROPIC_API_KEY=sk-ant-test\n",
                Files.readString(finalApp.resolve("secrets/agent-proxy.env")));
        assertFalse(Files.exists(finalApp.resolve("stale.txt")),
                "the wipe must still remove regenerable output -- otherwise the spare list proves nothing");
    }

    /**
     * R10 (EXT-1, "custom-screen mount"): the migrated mount -- previously a hand-rolled
     * Copy-Item loop in Build-NpdevApp.ps1's now-retired step 4b, now
     * {@link FinalAppAssembler#mountWebAssets}, reached via {@code Options#webAssetsRoot()}. Proves
     * a nested author screen lands under the App module's OWN {@code src/main/resources/static}
     * (never {@code npdev-generated/}, which {@code StrictExecutionValidator} hashes) and that the
     * generic exclusion filtering (a stray {@code .git} directory, {@code Thumbs.db}) still applies
     * to an asset tree the same way it already does for the RuntimeHost/generated-artifact copies.
     */
    @Test
    void mountsCompanionWebAssetsIntoStaticFolder() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-webassets-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path finalApp = workspace.resolve("FinalExec");
        Path webAssets = workspace.resolve("web");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");
        write(webAssets.resolve("custom-screen.html"), "<html>custom screen</html>\n");
        write(webAssets.resolve("assets/custom-screen.panel.json"), "{\"screen\":\"web/custom-screen.html\"}\n");
        // Must be filtered exactly like every other copy mode -- proves WEB_ASSETS still gets the
        // generic EXCLUDED_DIRECTORY_NAMES/EXCLUDED_FILE_NAMES pass, not an unfiltered raw copy.
        write(webAssets.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        write(webAssets.resolve("Thumbs.db"), "junk");

        FinalAppAssembler.AssemblyResult result = new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        host, artifact, finalApp, null, "npdev-generated", "npdev-meta", true, 17, webAssets
                )
        );

        assertTrue(Files.exists(finalApp.resolve("src/main/resources/static/custom-screen.html")));
        assertEquals("<html>custom screen</html>\n",
                Files.readString(finalApp.resolve("src/main/resources/static/custom-screen.html")));
        assertTrue(Files.exists(finalApp.resolve("src/main/resources/static/assets/custom-screen.panel.json")));
        assertFalse(Files.exists(finalApp.resolve("src/main/resources/static/.git")));
        assertFalse(Files.exists(finalApp.resolve("src/main/resources/static/Thumbs.db")));
        // Never under npdev-generated/ -- StrictExecutionValidator hashes that tree at boot.
        assertFalse(Files.exists(finalApp.resolve("npdev-generated/src/main/resources/static/custom-screen.html")));
        assertEquals(2, result.webAssetsFilesCopied());
    }

    /**
     * REG-167: an author's web/ directory containing a file at the same relative path as a
     * platform-reserved static/ name (shell.js, or anything under npdev-business-ui/) must refuse
     * assembly with a named error, rather than silently mounting a file that would collide
     * undefined-winner at build time (Gradle's default merge behavior for two srcDirs of the same
     * resource sourceSet).
     */
    @Test
    void refusesWebAssetsCollidingWithReservedPlatformStaticNames() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-webassets-collision-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path finalApp = workspace.resolve("FinalExec");
        Path webAssets = workspace.resolve("web");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");
        write(webAssets.resolve("shell.js"), "// author's own, unrelated shell.js\n");
        write(webAssets.resolve("npdev-business-ui/index.html"), "<html>author collision</html>\n");

        FinalAppAssembler assembler = new FinalAppAssembler();
        FinalAppAssembler.Options options = new FinalAppAssembler.Options(
                host, artifact, finalApp, null, "npdev-generated", "npdev-meta", true, 17, webAssets
        );
        java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> assembler.assemble(options));
        assertTrue(failure.getMessage().contains("shell.js"));
        assertTrue(failure.getMessage().contains("npdev-business-ui/index.html"));
        // mountWebAssets runs near the end of assemble() (after the RuntimeHost/artifact copies),
        // so the app dir legitimately exists by the time this refuses -- but neither colliding
        // author file was ever mounted into static/.
        assertFalse(Files.exists(finalApp.resolve("src/main/resources/static/shell.js")));
        assertFalse(Files.exists(finalApp.resolve("src/main/resources/static/npdev-business-ui")));
    }

    /** A caller that declares webAssetsRoot but points it at nothing is a caller bug -- fails loud,
     *  same discipline every other explicit path option here (runtimeHostRoot, generatedArtifactRoot)
     *  already requires via {@code requireDirectory}, rather than silently mounting nothing. */
    @Test
    void refusesToAssembleWhenWebAssetsRootIsDeclaredButMissing() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-webassets-missing-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path finalApp = workspace.resolve("FinalExec");
        Path missingWebAssets = workspace.resolve("does-not-exist");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");

        FinalAppAssembler assembler = new FinalAppAssembler();
        FinalAppAssembler.Options options = new FinalAppAssembler.Options(
                host, artifact, finalApp, null, "npdev-generated", "npdev-meta", true, 17, missingWebAssets
        );
        java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> assembler.assemble(options));
        assertTrue(failure.getMessage().contains("Web assets root"));
    }

    /**
     * BUILD-2 (BT-2's own "the linking" follow-on, ledger item BUILD-2): the assembly-level half of
     * linking, proven end to end -- a real sealed jar of the real {@code identity} pack (built by
     * {@link SealedPackJarBuilder}, the SAME class {@code SealedPackJarBuilderTest} proves is
     * byte-identical across independent builds) copied into the assembled app, and the generated
     * {@code @EntityScan}/{@code @ComponentScan} companion config naming its namespace -- with NO
     * edit needed to the (here, hand-written stub) {@code FinalExecApplication.java} for it to be
     * found, exactly as {@link FinalAppAssembler#linkSealedPacks} documents.
     */
    @Test
    void linksASealedPackJar_intoLibsSealedPacks_withEntityAndComponentScanConfig(@org.junit.jupiter.api.io.TempDir Path tempRoot) throws Exception {
        Path identityPackFile = Path.of("..", "..", "NPDevContract", "packs", "identity", "pack.json")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(identityPackFile), "expected " + identityPackFile + " to exist");

        Path sealedJar = tempRoot.resolve("identity-v1.jar");
        SealedPackJarBuilder.JarResult sealResult = new SealedPackJarBuilder().sealToJar(identityPackFile, sealedJar);

        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-sealedpack-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path finalApp = workspace.resolve("FinalExec");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(host.resolve("src/main/java/com/finalexec/FinalExecApplication.java"), "package com.finalexec;\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");

        FinalAppAssembler.Options options = new FinalAppAssembler.Options(
                host, artifact, finalApp, null, "npdev-generated", "npdev-meta", true, 17, null,
                List.of(new FinalAppAssembler.SealedPackLink("identity", sealedJar))
        );
        new FinalAppAssembler().assemble(options);

        Path linkedJar = finalApp.resolve("libs/sealed-packs/identity-1.0.0.jar");
        assertTrue(Files.isRegularFile(linkedJar), "expected the sealed pack jar to be copied into libs/sealed-packs/");
        assertArrayEqualsBytes(Files.readAllBytes(sealedJar), Files.readAllBytes(linkedJar));

        Path linkageConfig = finalApp.resolve("src/main/java/com/finalexec/config/SealedPackLinkageConfig.java");
        assertTrue(Files.isRegularFile(linkageConfig), "expected a generated SealedPackLinkageConfig.java");
        String source = Files.readString(linkageConfig);
        assertTrue(source.contains("package com.finalexec.config;"));
        assertTrue(source.contains("@Configuration"));
        assertTrue(source.contains("@EntityScan(basePackages = {\"" + sealResult.manifest().packageName() + "\"})"));
        assertTrue(source.contains("@ComponentScan(basePackages = {\"" + sealResult.manifest().packageName() + "\"})"));
        assertEquals("com.npdev.pack.identity.v1", sealResult.manifest().packageName());

        // FinalExecApplication.java itself is untouched -- SealedPackLinkageConfig is auto-detected
        // only because it lives under com.finalexec, already covered by that file's own
        // @ComponentScan(basePackages = {"com.finalexec", ...}).
        assertEquals("package com.finalexec;\n",
                Files.readString(finalApp.resolve("src/main/java/com/finalexec/FinalExecApplication.java")));
    }

    /**
     * BT-2's own ABI refusal ({@code PackAbiCompatibility.checkLinkable}), exercised through
     * assembly: a jar declaring a kernel ABI version other than {@code KernelAbi.CURRENT_ABI_VERSION}
     * must fail assembly loudly, before anything is copied or generated.
     */
    @Test
    void refusesToLinkASealedPackJarBuiltAgainstAnIncompatibleKernelAbi(@org.junit.jupiter.api.io.TempDir Path tempRoot) throws Exception {
        Path mismatchedJar = tempRoot.resolve("mismatched-abi.jar");
        writeFakeSealedPackJar(mismatchedJar, "widgets", "1.0.0", "1", "999-not-the-current-abi");

        Path workspace = Files.createTempDirectory("npdev-final-app-assembly-sealedpack-abi-");
        Path host = workspace.resolve("RuntimeHost");
        Path artifact = workspace.resolve("ArtifactNP");
        Path finalApp = workspace.resolve("FinalExec");

        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"),
                "{\"namespace\":\"demo.sample\",\"version\":\"1.0\",\"dslVersion\":\"1.0.0\"}\n");

        FinalAppAssembler.Options options = new FinalAppAssembler.Options(
                host, artifact, finalApp, null, "npdev-generated", "npdev-meta", true, 17, null,
                List.of(new FinalAppAssembler.SealedPackLink("widgets", mismatchedJar))
        );

        PackAbiIncompatibleException thrown = assertThrows(
                PackAbiIncompatibleException.class, () -> new FinalAppAssembler().assemble(options));
        assertTrue(thrown.getMessage().contains("widgets"));
        assertTrue(thrown.getMessage().contains("999-not-the-current-abi"));
        assertFalse(Files.exists(finalApp.resolve("libs/sealed-packs")),
                "an ABI-incompatible link must be refused before anything is copied");
        assertFalse(Files.exists(finalApp.resolve("src/main/java/com/finalexec/config/SealedPackLinkageConfig.java")));
    }

    private static void writeFakeSealedPackJar(
            Path jarFile, String packId, String packVersion, String packMajorVersion, String kernelAbiVersion
    ) throws IOException {
        Files.createDirectories(jarFile.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fileOut = Files.newOutputStream(jarFile);
             JarOutputStream jarOut = new JarOutputStream(fileOut, manifest)) {
            jarOut.putNextEntry(new JarEntry("META-INF/npdev-pack.properties"));
            String properties = "packId=" + packId + "\n"
                    + "packVersion=" + packVersion + "\n"
                    + "packMajorVersion=" + packMajorVersion + "\n"
                    + "kernelAbiVersion=" + kernelAbiVersion + "\n";
            jarOut.write(properties.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            jarOut.closeEntry();
        }
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
