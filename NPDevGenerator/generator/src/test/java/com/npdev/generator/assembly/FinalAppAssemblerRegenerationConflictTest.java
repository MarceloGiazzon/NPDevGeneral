package com.npdev.generator.assembly;

import com.npdev.generator.emitters.GeneratedContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path A P5.3 (NPDEV_PATH_A_REALIGNMENT_PLAN.md; docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md's
 * Promotion Workflow "allowed conflict outcomes: KeepCustom, ReplaceGenerated, AskUser, Block"):
 * proves {@link FinalAppAssembler#assemble} actually enforces those outcomes at regeneration, using a
 * hand-built {@code extension-inventory.json} (the real shape {@code ExtensionInventoryEmitter} writes)
 * rather than a full generator run, so each scenario isolates exactly the assembler-side behavior.
 *
 * <p>Every test runs {@code assemble()} TWICE against the SAME {@code finalApp} root: the first call
 * establishes "a previous generation happened" (the state {@link FinalAppAssembler} reads back next
 * time); the owner's generated file is then hand-edited between calls to stand in for a user
 * customization; the second call is the regeneration under test.
 */
class FinalAppAssemblerRegenerationConflictTest {

    private static final String RELATIVE_PATH = "src/main/java/JavaHookOwner.java";
    private static final String OWNER = "hook-1";

    @Test
    void keepCustomSurvivesRegenerationWhenIntentIsPreserve() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-p53-keepcustom-");
        Path host = hostFixture(workspace);
        Path finalApp = workspace.resolve("FinalExec");

        Path firstArtifact = workspace.resolve("Artifact1");
        write(firstArtifact.resolve(RELATIVE_PATH), "original generated content");
        writeInventory(firstArtifact, hashOf(firstArtifact, "original generated content"), true, "preserve");
        assemble(host, firstArtifact, finalApp);

        Path generatedMount = finalApp.resolve("npdev-generated");
        write(generatedMount.resolve(RELATIVE_PATH), "USER CUSTOMIZED CONTENT");

        Path secondArtifact = workspace.resolve("Artifact2");
        write(secondArtifact.resolve(RELATIVE_PATH), "regenerated content v2");
        writeInventory(secondArtifact, hashOf(secondArtifact, "regenerated content v2"), true, "preserve");
        assemble(host, secondArtifact, finalApp);

        assertEquals("USER CUSTOMIZED CONTENT", Files.readString(generatedMount.resolve(RELATIVE_PATH)),
                "KeepCustom must restore the pre-wipe content over the freshly generated file");
    }

    @Test
    void unchangedFileRegeneratesNormallyDespiteADeclaredPreserveIntent() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-p53-unchanged-");
        Path host = hostFixture(workspace);
        Path finalApp = workspace.resolve("FinalExec");

        Path firstArtifact = workspace.resolve("Artifact1");
        write(firstArtifact.resolve(RELATIVE_PATH), "original generated content");
        writeInventory(firstArtifact, hashOf(firstArtifact, "original generated content"), true, "preserve");
        assemble(host, firstArtifact, finalApp);

        // No hand-edit this time -- the file on disk still matches what was last generated.

        Path secondArtifact = workspace.resolve("Artifact2");
        write(secondArtifact.resolve(RELATIVE_PATH), "regenerated content v2");
        writeInventory(secondArtifact, hashOf(secondArtifact, "regenerated content v2"), true, "preserve");
        assemble(host, secondArtifact, finalApp);

        assertEquals("regenerated content v2",
                Files.readString(finalApp.resolve("npdev-generated").resolve(RELATIVE_PATH)),
                "an owner nobody customized must regenerate normally even with a standing preserve intent");
    }

    @Test
    void undeclaredDriftBlocksRegenerationBeforeAnythingIsDeleted() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-p53-undeclared-");
        Path host = hostFixture(workspace);
        Path finalApp = workspace.resolve("FinalExec");

        Path firstArtifact = workspace.resolve("Artifact1");
        write(firstArtifact.resolve(RELATIVE_PATH), "original generated content");
        writeInventory(firstArtifact, hashOf(firstArtifact, "original generated content"), true, "preserve");
        assemble(host, firstArtifact, finalApp);

        Path generatedMount = finalApp.resolve("npdev-generated");
        write(generatedMount.resolve(RELATIVE_PATH), "USER CUSTOMIZED CONTENT");
        Path markerFile = finalApp.resolve("untouched-marker.txt");
        write(markerFile, "must survive a blocked regeneration");

        Path secondArtifact = workspace.resolve("Artifact2");
        write(secondArtifact.resolve(RELATIVE_PATH), "regenerated content v2");
        // No provenance record declared this run -- undeclared drift.
        writeInventory(secondArtifact, hashOf(secondArtifact, "regenerated content v2"), false, null);

        Exception failure = assertThrows(Exception.class, () -> assemble(host, secondArtifact, finalApp));
        assertTrue(failure.getMessage().contains(OWNER), "failure must name the conflicting owner: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("undeclared"), "failure must say the drift is undeclared: " + failure.getMessage());

        assertEquals("USER CUSTOMIZED CONTENT", Files.readString(generatedMount.resolve(RELATIVE_PATH)),
                "Block must run before deleteTree -- the hand-edit must survive the failed attempt");
        assertTrue(Files.exists(markerFile), "Block must run before deleteTree -- unrelated files must survive too");
    }

    @Test
    void declaredAskBlocksRegenerationUntilResolved() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-p53-ask-");
        Path host = hostFixture(workspace);
        Path finalApp = workspace.resolve("FinalExec");

        Path firstArtifact = workspace.resolve("Artifact1");
        write(firstArtifact.resolve(RELATIVE_PATH), "original generated content");
        writeInventory(firstArtifact, hashOf(firstArtifact, "original generated content"), true, "preserve");
        assemble(host, firstArtifact, finalApp);

        Path generatedMount = finalApp.resolve("npdev-generated");
        write(generatedMount.resolve(RELATIVE_PATH), "USER CUSTOMIZED CONTENT");

        Path secondArtifact = workspace.resolve("Artifact2");
        write(secondArtifact.resolve(RELATIVE_PATH), "regenerated content v2");
        writeInventory(secondArtifact, hashOf(secondArtifact, "regenerated content v2"), true, "ask");

        Exception failure = assertThrows(Exception.class, () -> assemble(host, secondArtifact, finalApp));
        assertTrue(failure.getMessage().contains("ask"), "failure must name the standing ask intent: " + failure.getMessage());

        assertEquals("USER CUSTOMIZED CONTENT", Files.readString(generatedMount.resolve(RELATIVE_PATH)),
                "AskUser must run before deleteTree -- the hand-edit must survive the failed attempt");
    }

    private static Path hostFixture(Path workspace) throws Exception {
        Path host = workspace.resolve("RuntimeHost");
        write(host.resolve("build.gradle.template"), "plugins { id 'java' }\n");
        return host;
    }

    private static void assemble(Path host, Path artifact, Path finalApp) throws Exception {
        new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        host, artifact, finalApp, null, "npdev-generated", "npdev-meta", true, 17, null
                )
        );
    }

    private static String hashOf(Path artifactRoot, String content) throws Exception {
        write(artifactRoot.resolve(RELATIVE_PATH), content);
        return GeneratedContentHash.of(artifactRoot, List.of(RELATIVE_PATH));
    }

    private static void writeInventory(Path artifactRoot, String contentHash, boolean declared, String regenerationIntent)
            throws Exception {
        String provenance = declared
                ? """
                    "provenance": {
                      "declared": true,
                      "changeSummary": "x",
                      "reason": "x",
                      "author": "x",
                      "authorType": "human",
                      "regenerationIntent": "%s",
                      "requiresRetest": false,
                      "releaseImpact": "none"
                    }
                    """.formatted(regenerationIntent)
                : """
                    "provenance": { "declared": false }
                    """;
        String json = """
                {
                  "schemaVersion": "1.0",
                  "counts": { "javaHook": 1 },
                  "entries": [
                    {
                      "category": "javaHook",
                      "kind": "conversionJavaHook",
                      "origin": "hooks/JavaHookOwner.java#invoke",
                      "owner": "%s",
                      "generatedPaths": ["%s"],
                      "contentHash": "%s",
                      %s
                    }
                  ]
                }
                """.formatted(OWNER, RELATIVE_PATH, contentHash, provenance);
        write(artifactRoot.resolve("src/main/resources/npdev/extension-inventory.json"), json);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
