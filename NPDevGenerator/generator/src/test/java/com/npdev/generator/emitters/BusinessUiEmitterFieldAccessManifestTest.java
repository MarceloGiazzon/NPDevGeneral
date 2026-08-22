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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-2: R5.5 shipped SERVER-enforced {@code field.access {read, write}} (a denied read is stripped
 * from the response, a denied write throws {@code FIELD_SCOPE_DENIED}) but left the generated UI
 * metadata with no idea a field was gated at all -- its own commit message named this exactly:
 * "for screens to hide or disable coherently, the resolved flags must reach the generated UI metadata
 * bundle. That is a NPDevGenerator change this commit does not make." This test is that change's
 * proof: {@link BusinessUiEmitter} threads {@code CompiledField#getAccess()} into two new manifest
 * booleans, {@code accessReadScoped}/{@code accessWriteScoped}, real per-field DSL declarations
 * compiled through the actual {@link JsonModelParser}/{@link ModelCompiler} pipeline -- not a
 * hand-built {@code CompiledField}.
 *
 * <p>These flags are declared-presence, not a per-request evaluation of the rule -- the rule may
 * reference the caller's role/tenant/row-owner (see {@code $user.roles == 'MANAGER'} below), none of
 * which generation time has. business-ui-app.mustache's {@code isFieldReadonlyByCondition} treats
 * {@code accessWriteScoped} as an unconditional read-only render precisely because it has no way to
 * evaluate the declared rule itself -- a courtesy, never a security control; the server enforces
 * regardless of what the generated UI renders.
 */
class BusinessUiEmitterFieldAccessManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void accessScopedFlagsReachTheGeneratedUiManifest(@TempDir Path tempDir) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "field.access.ui.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Payroll",
                      "ui": { "label": "Payroll" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "employeeName", "type": "string", "required": true },
                        {
                          "name": "salary",
                          "type": "decimal",
                          "precision": 12,
                          "scale": 2,
                          "access": {
                            "read": "$user.roles == 'MANAGER'",
                            "write": "$user.roles == 'MANAGER'"
                          }
                        },
                        {
                          "name": "ssn",
                          "type": "string",
                          "access": { "read": "$user.roles == 'MANAGER'" }
                        },
                        {
                          "name": "bonusApproved",
                          "type": "boolean",
                          "access": { "write": "$user.roles == 'MANAGER'" }
                        }
                      ]
                    }
                  ]
                }
                """);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-field-access-ui-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(compiled, "ADMIN", new SettingResolver(SettingStore.empty()));

        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json").toFile());
        JsonNode fields = fieldsOf(manifest, "Payroll");

        assertAccessFlags(fields, "employeeName", false, false);
        assertAccessFlags(fields, "salary", true, true);
        assertAccessFlags(fields, "ssn", true, false);
        assertAccessFlags(fields, "bonusApproved", false, true);
    }

    private static JsonNode fieldsOf(JsonNode manifest, String conceptName) {
        for (JsonNode concept : manifest.path("concepts")) {
            if (conceptName.equals(concept.path("conceptName").asText())) {
                return concept.path("fields");
            }
        }
        throw new AssertionError("concept '" + conceptName + "' not found in manifest: " + manifest);
    }

    private static void assertAccessFlags(JsonNode fields, String fieldName, boolean expectRead, boolean expectWrite) {
        for (JsonNode field : fields) {
            if (fieldName.equals(field.path("name").asText())) {
                if (expectRead) {
                    assertTrue(field.path("accessReadScoped").asBoolean(), fieldName + ".accessReadScoped");
                } else {
                    assertFalse(field.path("accessReadScoped").asBoolean(), fieldName + ".accessReadScoped");
                }
                if (expectWrite) {
                    assertTrue(field.path("accessWriteScoped").asBoolean(), fieldName + ".accessWriteScoped");
                } else {
                    assertFalse(field.path("accessWriteScoped").asBoolean(), fieldName + ".accessWriteScoped");
                }
                return;
            }
        }
        throw new AssertionError("field '" + fieldName + "' not found");
    }

    @Test
    void aFieldWithNoAccessDeclarationCarriesBothFlagsFalse_backwardCompatibility(@TempDir Path tempDir) throws Exception {
        // Regression guard: every pre-existing model with no field.access at all must see both flags
        // present-and-false, never absent (uniform manifest shape, same convention extensionSource
        // already established) and never an NPE from a null CompiledFieldAccess.
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "field.access.ui.none.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "title", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-field-access-ui-none-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(compiled, "ADMIN", new SettingResolver(SettingStore.empty()));

        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json").toFile());
        assertAccessFlags(fieldsOf(manifest, "Widget"), "title", false, false);
    }
}
