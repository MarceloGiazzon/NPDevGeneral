package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrustedSourceEmitterUiExecutionMetadataVisibilityTest {
    @TempDir
    Path tempDir;

    @Test
    void generatedPanelBridgeRendersActionExecutionMetadataTruthfully() throws Exception {
        Path modelRoot = tempDir.resolve("model");
        Files.createDirectories(modelRoot.resolve("trusted"));
        Files.createDirectories(modelRoot.resolve("panel"));
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");

        Path procedure = modelRoot.resolve("trusted/CreateItem13UserProcedure.java");
        Files.writeString(procedure, """
                import java.util.Map;

                public final class CreateItem13UserProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        return Map.of("createdCount", 1);
                    }
                }
                """);

        Path panel = modelRoot.resolve("panel/item13-panel.html");
        Files.writeString(panel, """
                <!doctype html>
                <html>
                  <body>
                    <main>
                      <button id="createItem13">Create</button>
                      <script>
                        window.NPDev.callProcedure("CreateItem13User", {
                          executionId: "exec-ui",
                          correlationId: "corr-ui"
                        });
                      </script>
                    </main>
                  </body>
                </html>
                """);

        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), manifest(sha256(procedure), sha256(panel)));

        Path out = tempDir.resolve("out");
        new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model(), modelPath);

        Path trustedRoot = out.resolve("src/main/java/com/npdev/generated/trusted");
        String controller = Files.readString(trustedRoot.resolve("GeneratedTrustedSourceRuntimeController.java"));
        String response = Files.readString(trustedRoot.resolve("GeneratedActionExecutionResponse.java"));
        String runner = Files.readString(trustedRoot.resolve("GeneratedActionKernelRunner.java"));
        String html = Files.readString(out.resolve("src/main/resources/trusted-source/panel/item13-panel.html"));
        String js = Files.readString(out.resolve("src/main/resources/trusted-source/panel/item13-panel.js"));

        assertTrue(html.contains("/generated/trusted-source/panel/item13-panel.js"));
        assertFalse(html.matches("(?s).*<script(?![^>]*\\bsrc\\s*=)[^>]*>.*"),
                "Panel source must remain externalized after sanitizer");
        assertTrue(js.contains("window.NPDev.callProcedure"));

        assertTrue(response.contains("String capabilityId"));
        assertTrue(response.contains("String capabilityDispatchStatus"));
        assertTrue(response.contains("out.put(\"capabilityId\", capabilityId)"));
        assertTrue(response.contains("out.put(\"capabilityDispatchStatus\", capabilityDispatchStatus)"));
        assertTrue(runner.contains("dispatched: capabilityId="));
        assertTrue(runner.contains("prevented: idempotency reused before capability dispatch"));

        assertTrue(controller.contains("window.NPDev.callProcedure = async function(name, payload)"));
        assertTrue(controller.contains("window.NPDev.renderActionResultHtml = function(response)"));
        assertTrue(controller.contains("window.NPDev.renderActionResult = function(container, response)"));
        assertTrue(controller.contains("'/generated/procedures/' + encodeURIComponent(name)"));
        assertTrue(controller.contains("window.NPDev.renderActionResult(null, body)"));
        assertTrue(controller.contains("error.responseBody = body"));

        for (String hook : List.of(
                "data-npdev-action-result",
                "data-npdev-execution-id",
                "data-npdev-correlation-id",
                "data-npdev-action-name",
                "data-npdev-procedure-name",
                "data-npdev-capability-id",
                "data-npdev-dispatch-status",
                "data-npdev-event-status",
                "data-npdev-trace-status",
                "data-npdev-audit-status",
                "data-npdev-idempotency-status",
                "data-npdev-correlation-status",
                "data-npdev-created-count",
                "data-npdev-side-effect-before",
                "data-npdev-side-effect-after",
                "data-npdev-message",
                "data-npdev-error",
                "data-npdev-execution-evidence-link",
                "data-npdev-correlation-evidence-link",
                "data-npdev-evidence-link-status"
        )) {
            assertTrue(controller.contains(hook), "Missing generated metadata hook " + hook);
        }

        assertTrue(controller.contains("View execution evidence"));
        assertTrue(controller.contains("View correlation evidence"));
        assertTrue(controller.contains("/generated/actions/executions/"));
        assertTrue(controller.contains("/generated/actions/correlations/"));
        assertTrue(controller.contains("Evidence link unavailable: executionId/correlationId not returned by runtime"));
        assertTrue(controller.contains("unavailable: not returned by runtime"));
        assertTrue(controller.contains("unavailable: runtime returned null"));
        assertTrue(controller.contains("Action failed"));
        assertTrue(controller.contains("Action reused / duplicate prevented"));
        assertTrue(controller.contains("Action completed"));
        assertTrue(controller.contains("npdevFieldValue(response, 'idempotencyStatus').toLowerCase()"));
    }

    private static CompiledModel model() {
        CompiledProcedure procedure = new CompiledProcedure(
                "CreateItem13User",
                "Create Item 13 user",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("ADMIN"),
                "record",
                "write",
                new CompiledGeneratedActionDescriptorSpec(
                        "CreateItem13User",
                        List.of("Item13User"),
                        "Item13User",
                        "generated.action.item13-user.completed",
                        "ITEM13_USER",
                        "record",
                        "record",
                        "claim",
                        true
                ),
                Map.of("trustedSourceEntrypoint", "trusted/CreateItem13UserProcedure.java")
        );
        CompiledPanel panel = new CompiledPanel(
                "item13-panel",
                "/item13-panel",
                "Item 13 Panel",
                List.of(),
                null,
                List.of(),
                "",
                "",
                List.of(),
                Map.of(),
                Map.of("trustedSourceEntrypoint", "panel/item13-panel.html"),
                null
        );
        return new CompiledModel(
                "item13.ui.metadata",
                "1.0.0",
                "1.0.0",
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
                  "schemaVersion": "npdev-trusted-source-manifest.v1",
                  "entries": [
                    {
                      "entryId": "procedure-create-item13-user",
                      "kind": "procedure",
                      "relativePath": "trusted/CreateItem13UserProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:CreateItem13User",
                      "className": "CreateItem13UserProcedure",
                      "method": "execute",
                      "requiredRole": "ADMIN",
                      "tenantScoped": true
                    },
                    {
                      "entryId": "panel-item13",
                      "kind": "panel",
                      "relativePath": "panel/item13-panel.html",
                      "language": "html",
                      "sha256": "%s",
                      "runtimeBinding": "panel:/item13-panel",
                      "className": "",
                      "method": "",
                      "requiredRole": "ADMIN",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(procedureHash, panelHash);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
