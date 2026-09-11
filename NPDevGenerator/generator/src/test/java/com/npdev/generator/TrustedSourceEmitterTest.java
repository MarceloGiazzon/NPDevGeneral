package com.npdev.generator;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.emitters.TrustedSourceEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedSourceEmitterTest {

    @Test
    void emitsTrustedSourceArtifactsFromSiblingManifest() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-source-model-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path procedure = modelRoot.resolve("procedure/CreateUsersProcedure.java");
        Path panel = modelRoot.resolve("panel/user-admin-panel.html");
        Files.createDirectories(procedure.getParent());
        Files.createDirectories(panel.getParent());
        Files.writeString(procedure, """
                import java.util.List;
                import java.util.Map;

                public final class CreateUsersProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        return Map.of("createdCount", 0, "users", List.of());
                    }
                }
                """);
        Files.writeString(panel, """
                <!doctype html>
                <html>
                  <head>
                    <style>
                      body { color: #111; }
                    </style>
                  </head>
                  <body>
                    <button id="createUsers">Create</button>
                    <script>
                      window.NPDev.callProcedure("create-users", {});
                    </script>
                  </body>
                </html>
                """);
        Files.writeString(modelRoot.resolve("untrusted-extension-manifest.json"), manifest(sha256(procedure), sha256(panel)));

        Path out = Files.createTempDirectory("npdev-trusted-source-out-");
        new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model(), modelPath);

        assertTrue(Files.isRegularFile(out.resolve("src/main/java/com/npdev/generated/trusted/CreateUsersProcedure.java")));
        assertTrue(Files.isRegularFile(out.resolve("src/main/java/com/npdev/generated/trusted/NPDevProcedureContext.java")));
        assertTrue(Files.isRegularFile(out.resolve("src/main/java/com/npdev/generated/trusted/GeneratedTrustedSourceRuntimeController.java")));
        assertTrue(Files.isRegularFile(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.html")));
        assertTrue(Files.isRegularFile(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.css")));
        assertTrue(Files.isRegularFile(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.js")));
        String controller = Files.readString(out.resolve("src/main/java/com/npdev/generated/trusted/GeneratedTrustedSourceRuntimeController.java"));
        assertTrue(controller.contains("actionKernelRunner.run("));
        assertTrue(!controller.contains("new CreateUsersProcedure().execute"));
        assertTrue(controller.contains("RuntimeContextService"));
        assertTrue(controller.contains("@GetMapping(value = \"/generated/trusted-source/state/{conceptName}\""));
        assertTrue(controller.contains("rejectIfUnauthorized(context, Map.of(), \"ADMIN\", false, 0)"));
        String html = Files.readString(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.html"));
        String css = Files.readString(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.css"));
        String js = Files.readString(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.js"));
        assertTrue(html.contains("/generated/trusted-source/panel/user-admin-panel.css"));
        assertTrue(html.contains("/generated/trusted-source/panel/user-admin-panel.js"));
        assertTrue(!html.contains("<style"));
        assertTrue(!html.matches("(?s).*<script(?![^>]*\\bsrc\\s*=)[^>]*>.*"));
        assertTrue(css.contains("body { color: #111; }"));
        assertTrue(js.contains("window.NPDev.callProcedure"));
    }

    @Test
    void emitsWhenTruthStatusClaimIsBackedByADeclaredTest() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-source-truth-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path procedure = modelRoot.resolve("procedure/CreateUsersProcedure.java");
        Path panel = modelRoot.resolve("panel/user-admin-panel.html");
        Files.createDirectories(procedure.getParent());
        Files.createDirectories(panel.getParent());
        Files.writeString(procedure, """
                import java.util.List;
                import java.util.Map;

                public final class CreateUsersProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        return Map.of("createdCount", 0, "users", List.of());
                    }
                }
                """);
        Files.writeString(panel, "<!doctype html><html><body></body></html>");
        Path test = modelRoot.resolve("tests/CreateUsersProcedureTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "// proof that a T4 truthStatus claim has real test evidence behind it");
        Files.writeString(modelRoot.resolve("untrusted-extension-manifest.json"), """
                {
                  "schemaVersion": "npdev-untrusted-extension-manifest.v1",
                  "scenarioId": "trusted-source-truth",
                  "policyVersion": "test",
                  "expectedOutcome": "pass",
                  "entries": [
                    {
                      "entryId": "procedure-create-users",
                      "kind": "procedure",
                      "relativePath": "procedure/CreateUsersProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:create-users",
                      "className": "CreateUsersProcedure",
                      "method": "execute",
                      "requiredRole": "admin",
                      "tenantScoped": true,
                      "inputs": [],
                      "outputs": ["createdCount", "users"],
                      "dependencies": ["concept:User"],
                      "tests": ["tests/CreateUsersProcedureTest.java"],
                      "truthStatus": "T4"
                    },
                    {
                      "entryId": "panel-user-admin",
                      "kind": "panel",
                      "relativePath": "panel/user-admin-panel.html",
                      "language": "html+javascript",
                      "sha256": "%s",
                      "runtimeBinding": "panel:/users",
                      "requiredRole": "admin",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(sha256(procedure), sha256(panel)));

        Path out = Files.createTempDirectory("npdev-trusted-source-out-");
        new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model(), modelPath);

        assertTrue(Files.isRegularFile(out.resolve("src/main/java/com/npdev/generated/trusted/CreateUsersProcedure.java")));
        String generationManifest = Files.readString(
                out.resolve("src/main/resources/trusted-source/trusted-source-generation-manifest.json"));
        assertTrue(generationManifest.contains("truthStatus"));
        assertTrue(generationManifest.contains("T4"));
        assertTrue(generationManifest.contains("tests/CreateUsersProcedureTest.java"));
        assertTrue(generationManifest.contains("concept:User"));
    }

    @Test
    void failsClosedWhenTruthStatusClaimsEvidenceWithNoDeclaredTest() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-source-untested-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path procedure = modelRoot.resolve("procedure/CreateUsersProcedure.java");
        Files.createDirectories(procedure.getParent());
        Files.writeString(procedure, "public final class CreateUsersProcedure { public void execute() {} }");
        Files.writeString(modelRoot.resolve("untrusted-extension-manifest.json"), """
                {
                  "schemaVersion": "npdev-untrusted-extension-manifest.v1",
                  "scenarioId": "trusted-source-untested",
                  "policyVersion": "test",
                  "expectedOutcome": "fail",
                  "entries": [
                    {
                      "entryId": "procedure-create-users",
                      "kind": "procedure",
                      "relativePath": "procedure/CreateUsersProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:create-users",
                      "className": "CreateUsersProcedure",
                      "method": "execute",
                      "requiredRole": "admin",
                      "tenantScoped": true,
                      "truthStatus": "T5"
                    }
                  ]
                }
                """.formatted(sha256(procedure)));
        Path out = Files.createTempDirectory("npdev-trusted-source-out-");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model(), modelPath));
        assertTrue(failure.getMessage().contains("T5"));
        assertTrue(failure.getMessage().contains("test"));
    }

    @Test
    void failsClosedWhenTrustedReferenceIsMissingFromManifest() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-source-missing-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Files.writeString(modelRoot.resolve("untrusted-extension-manifest.json"), """
                {
                  "schemaVersion": "npdev-untrusted-extension-manifest.v1",
                  "scenarioId": "missing",
                  "policyVersion": "test",
                  "expectedOutcome": "fail",
                  "entries": [
                    {
                      "entryId": "other",
                      "kind": "procedure",
                      "relativePath": "procedure/OtherProcedure.java",
                      "language": "java",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "runtimeBinding": "procedure:other",
                      "className": "OtherProcedure",
                      "method": "execute",
                      "requiredRole": "admin",
                      "tenantScoped": true
                    }
                  ]
                }
                """);
        Path out = Files.createTempDirectory("npdev-trusted-source-out-");

        assertThrows(IllegalStateException.class,
                () -> new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model(), modelPath));
    }

    private static CompiledModel model() {
        CompiledProcedure procedure = new CompiledProcedure(
                "create-users",
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("admin"),
                "summary",
                Map.of("untrustedExtensionEntrypoint", "procedure/CreateUsersProcedure.java")
        );
        CompiledPanel panel = new CompiledPanel(
                "user-admin-panel",
                "/users",
                "Users",
                List.of(),
                null,
                List.of(),
                "role:admin",
                "",
                List.of(),
                Map.of(),
                Map.of("untrustedExtensionEntrypoint", "panel/user-admin-panel.html"),
                null
        );
        return new CompiledModel(
                "trusted.source.test",
                "1.0.0",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(procedure),
                List.of(panel)
        );
    }

    private static String manifest(String procedureHash, String panelHash) {
        return """
                {
                  "schemaVersion": "npdev-untrusted-extension-manifest.v1",
                  "scenarioId": "trusted-source",
                  "policyVersion": "test",
                  "expectedOutcome": "pass",
                  "entries": [
                    {
                      "entryId": "procedure-create-users",
                      "kind": "procedure",
                      "relativePath": "procedure/CreateUsersProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:create-users",
                      "className": "CreateUsersProcedure",
                      "method": "execute",
                      "requiredRole": "admin",
                      "tenantScoped": true
                    },
                    {
                      "entryId": "panel-user-admin",
                      "kind": "panel",
                      "relativePath": "panel/user-admin-panel.html",
                      "language": "html+javascript",
                      "sha256": "%s",
                      "runtimeBinding": "panel:/users",
                      "requiredRole": "admin",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(procedureHash, panelHash);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }
}
