package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "widget" trusted-source kind: a field opts a genuinely custom, author-supplied JS control
 * into the generated business UI via {@code ui.widget: "custom"} + {@code ui.customWidgetRef}.
 * Reuses the exact same hash-lock (fails closed on drift) and JS safety scan (banned eval/
 * external-fetch/etc.) already built and tested for trusted-source panels, per a new manifest
 * entry kind rather than a parallel mechanism.
 */
class TrustedSourceEmitterWidgetKindTest {

    private static Path writeModel(Path modelRoot) throws Exception {
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "trusted.widget.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Review",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "stars",
                          "type": "int",
                          "ui": { "label": "Stars", "widget": "custom", "customWidgetRef": "widgets/star-rating.js" }
                        }
                      ]
                    }
                  ]
                }
                """);
        return modelPath;
    }

    private static void writeWidgetScript(Path modelRoot, String source) throws Exception {
        Path widget = modelRoot.resolve("widgets/star-rating.js");
        Files.createDirectories(widget.getParent());
        Files.writeString(widget, source);
    }

    private static void writeManifest(Path modelRoot, String sha256) throws Exception {
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), """
                {
                  "schemaVersion": "npdev-trusted-source-manifest.v1",
                  "scenarioId": "trusted-widget",
                  "policyVersion": "test",
                  "expectedOutcome": "pass",
                  "entries": [
                    {
                      "entryId": "widget-star-rating",
                      "kind": "widget",
                      "relativePath": "widgets/star-rating.js",
                      "language": "javascript",
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(sha256));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static CompiledModel compile(Path modelPath) throws Exception {
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new ModelCompiler().compile(ast);
    }

    private static final String VALID_WIDGET_SOURCE = """
            window.NpdevCustomWidgets.register("widgets/star-rating.js", {
              render: function (field, value) {
                var input = document.createElement("input");
                input.name = field.name;
                input.type = "number";
                input.value = value || "";
                return input;
              }
            });
            """;

    @Test
    void emitsTheWidgetScriptAndServesItFromAGeneratedEndpoint() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-widget-");
        writeWidgetScript(modelRoot, VALID_WIDGET_SOURCE);
        writeManifest(modelRoot, sha256(modelRoot.resolve("widgets/star-rating.js")));
        Path modelPath = writeModel(modelRoot);
        CompiledModel model = compile(modelPath);

        Path out = Files.createTempDirectory("npdev-trusted-widget-out-");
        new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model, modelPath);

        Path writtenScript = out.resolve("src/main/resources/trusted-source/widget/widgets/star-rating.js");
        assertTrue(Files.isRegularFile(writtenScript), "expected the widget script to be written to the resource tree");
        assertTrue(Files.readString(writtenScript).contains("NpdevCustomWidgets.register"));

        String controller = Files.readString(out.resolve("src/main/java/com/npdev/generated/trusted/GeneratedTrustedSourceRuntimeController.java"));
        assertTrue(controller.contains("/generated/trusted-source/widget/widgets/star-rating.js"),
                "expected a generated GET mapping serving the widget script:\n" + controller);
    }

    @Test
    void failsClosedWhenTheWidgetFileDriftsFromItsDeclaredHash() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-widget-drift-");
        writeWidgetScript(modelRoot, VALID_WIDGET_SOURCE);
        // Hash computed against the ORIGINAL content, then the file is tampered afterward --
        // mirrors a regeneration running against a since-edited widget file.
        writeManifest(modelRoot, sha256(modelRoot.resolve("widgets/star-rating.js")));
        writeWidgetScript(modelRoot, VALID_WIDGET_SOURCE + "\n// tampered\n");
        Path modelPath = writeModel(modelRoot);
        CompiledModel model = compile(modelPath);

        Path out = Files.createTempDirectory("npdev-trusted-widget-drift-out-");
        assertThrows(IllegalStateException.class,
                () -> new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model, modelPath));
    }

    @Test
    void rejectsAWidgetScriptUsingABannedConstruct() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-widget-unsafe-");
        String unsafeSource = VALID_WIDGET_SOURCE + "\neval(\"1 + 1\");\n";
        writeWidgetScript(modelRoot, unsafeSource);
        writeManifest(modelRoot, sha256(modelRoot.resolve("widgets/star-rating.js")));
        Path modelPath = writeModel(modelRoot);
        CompiledModel model = compile(modelPath);

        Path out = Files.createTempDirectory("npdev-trusted-widget-unsafe-out-");
        assertThrows(IllegalStateException.class,
                () -> new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model, modelPath));
    }
}
