package com.npdev.generator.assembly;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        write(host.resolve("src/main/java/com/finalexec/FinalExecApplication.java"), "package com.finalexec;\n");
        write(host.resolve("src/main/java/com/finalexec/api/RuntimeMetadataController.java"), "package com.finalexec.api;\n");
        write(host.resolve("src/main/java/com/finalexec/api/internal/TemplateLibraryManagementController.java"), "package com.finalexec.api.internal;\n");
        write(host.resolve("src/main/java/com/finalexec/api/experimental/FlowBuilderController.java"), "package com.finalexec.api.experimental;\n");
        write(host.resolve("src/main/java/com/finalexec/npdev/service/internal/ModelSyncStatusService.java"), "package com.finalexec.npdev.service.internal;\n");
        write(host.resolve("src/main/java/com/finalexec/npdev/service/experimental/FlowBuilderService.java"), "package com.finalexec.npdev.service.experimental;\n");
        write(host.resolve("src/main/java/com/finalexec/HelloController.java"), "package com.finalexec;\n");
        write(host.resolve("src/main/resources/db/migration/V5001__runtime.sql"), "select 1;\n");
        write(host.resolve("libs/kernel-0.1.0.jar"), "jar");
        write(host.resolve(".gradle/cache.bin"), "cache");
        write(host.resolve(".idea/workspace.xml"), "idea");
        write(host.resolve("build/classes/Main.class"), "class");
        write(host.resolve("npdev-generated/src/main/java/Old.java"), "old generated");
        write(host.resolve("npdev-meta/export-manifest.txt"), "old meta");
        write(host.resolve("npdev-build-info.properties"), "old build info");

        write(artifact.resolve("src/main/java/com/npdev/generated/entities/User.java"), "package com.npdev.generated.entities;\n");
        write(artifact.resolve("src/main/resources/npdev/compiled-model.json"), "{}\n");
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
                        true
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
        assertFalse(Files.exists(finalApp.resolve("src/main/java/com/finalexec/HelloController.java")));
        assertFalse(Files.exists(finalApp.resolve("libs/kernel-0.1.0.jar")));
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
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
