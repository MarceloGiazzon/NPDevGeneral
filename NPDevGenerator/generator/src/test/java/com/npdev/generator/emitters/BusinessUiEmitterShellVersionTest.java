package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path A P6.1: the shell (business-ui-index/app/style + shell.js/shell.css) is a versioned
 * platform artifact, not an anonymous per-app copy -- see NpdevUiShellVersion and
 * docs/UI_CONTRACT.md's "Shell versioning". Proves (1) shell-manifest.json carries the declared
 * version and compat range, (2) each shell file carries the same version identity inline, and
 * (3) the shell stays byte-identical across two independent generations of two DIFFERENT models
 * (the property check-deterministic-generation.ps1 checks at the whole-app level for one model;
 * this pins it specifically for the shell files at the unit level, and across models).
 */
class BusinessUiEmitterShellVersionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL_A = """
            {
              "namespace": "shell.version.demo.a",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Widget",
                  "ui": { "label": "Widget" },
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true }
                  ]
                }
              ]
            }
            """;

    private static final String MODEL_B = """
            {
              "namespace": "shell.version.demo.b",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Gadget",
                  "ui": { "label": "Gadget" },
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string", "required": true }
                  ]
                }
              ]
            }
            """;

    @Test
    void shellManifestDeclaresVersionAndCompatRange(@TempDir Path tempDir) throws Exception {
        Path out = emit(tempDir, MODEL_A);

        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/static/npdev-business-ui/shell-manifest.json").toFile());
        assertEquals(NpdevUiShellVersion.SHELL_VERSION, manifest.path("shellVersion").asText());
        assertEquals(NpdevUiShellVersion.UI_CONTRACT_COMPAT_MIN, manifest.path("uiContractCompat").path("min").asText());
        assertEquals(NpdevUiShellVersion.UI_CONTRACT_COMPAT_MAX, manifest.path("uiContractCompat").path("max").asText());
    }

    @Test
    void everyShellFileCarriesTheSameVersionIdentityInline(@TempDir Path tempDir) throws Exception {
        Path out = emit(tempDir, MODEL_A);
        Path uiRoot = out.resolve("src/main/resources/static/npdev-business-ui");
        Path staticRoot = out.resolve("src/main/resources/static");

        assertContainsVersion(uiRoot.resolve("index.html"));
        assertContainsVersion(uiRoot.resolve("app.js"));
        assertContainsVersion(uiRoot.resolve("style.css"));
        assertContainsVersion(staticRoot.resolve("shell.js"));
        assertContainsVersion(staticRoot.resolve("shell.css"));
    }

    @Test
    void shellTopbarCarriesALiveClock(@TempDir Path tempDir) throws Exception {
        // WMS-9 N4: the shell chrome had no live date/time anywhere -- a generic, platform-wide gap,
        // not app-specific.
        Path out = emit(tempDir, MODEL_A);
        String shellJs = Files.readString(out.resolve("src/main/resources/static/shell.js"));
        assertTrue(shellJs.contains("npdev-shell-clock"), "topbar should carry a clock element");
        assertTrue(shellJs.contains("setInterval(updateClock"), "clock should tick on an interval");
    }

    @Test
    void shellTopbarCarriesAQuickAccessAffordance(@TempDir Path tempDir) throws Exception {
        // WMS-9 N5: the topbar previously had no quick-access affordance distinct from the sidebar
        // nav itself -- reuses the existing recentItems()/recordRecentItem() localStorage list (also
        // read by the right-rail "recent-items" gadget) rather than introducing a new tracked concept.
        Path out = emit(tempDir, MODEL_A);
        String shellJs = Files.readString(out.resolve("src/main/resources/static/shell.js"));
        assertTrue(shellJs.contains("npdev-shell-quickaccess-toggle"), "topbar should carry a quick-access toggle");
        assertTrue(shellJs.contains("renderQuickAccessPanel"), "quick-access toggle should render a panel");
        assertTrue(shellJs.contains("function recentItems()"), "quick-access panel must reuse the existing recentItems() reader, not a new store");
    }

    @Test
    void shellTopbarOffersChangePasswordOnlyForBearerScheme(@TempDir Path tempDir) throws Exception {
        // WMS-9 N6: the identity area had a "Sair" (logout) button but no way to change your own
        // password without leaving the app. Gated on the manifest's own auth.scheme (not baked in
        // per-model, so this file stays byte-identical across models per the test below) since an
        // apiKey app's token is a static dev key with no per-user credential to change.
        Path out = emit(tempDir, MODEL_A);
        String shellJs = Files.readString(out.resolve("src/main/resources/static/shell.js"));
        assertTrue(shellJs.contains("npdev-shell-change-password"), "identity area should carry a change-password link");
        assertTrue(shellJs.contains("/change-password.html"), "the link must point at the emitted self-service page");
        assertTrue(shellJs.contains("state.manifest.auth.scheme === \"Bearer\""),
                "the link must be gated on the jwt-mode (Bearer) scheme, not shown unconditionally");
    }

    @Test
    void shellFilesAreByteIdenticalAcrossDifferentModels(@TempDir Path tempDirA, @TempDir Path tempDirB) throws Exception {
        Path outA = emit(tempDirA, MODEL_A);
        Path outB = emit(tempDirB, MODEL_B);

        for (String relative : new String[] {
                "src/main/resources/static/npdev-business-ui/index.html",
                "src/main/resources/static/npdev-business-ui/app.js",
                "src/main/resources/static/npdev-business-ui/style.css",
                "src/main/resources/static/npdev-business-ui/shell-manifest.json",
                "src/main/resources/static/shell.js",
                "src/main/resources/static/shell.css",
        }) {
            String contentA = Files.readString(outA.resolve(relative));
            String contentB = Files.readString(outB.resolve(relative));
            assertEquals(contentA, contentB, relative + " must be byte-identical across models (it carries no model data)");
        }
    }

    private static void assertContainsVersion(Path file) throws Exception {
        String content = Files.readString(file);
        assertTrue(content.contains(NpdevUiShellVersion.SHELL_VERSION),
                file + " does not carry the shell version " + NpdevUiShellVersion.SHELL_VERSION);
    }

    private static Path emit(Path tempDir, String modelJson) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, modelJson);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-shell-version-ui-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(compiled, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }
}
