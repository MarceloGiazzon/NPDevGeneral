package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 3 (NPDEV_FEATURE_PLAN_2026-09-24): caught live on Pigmentampa's RegisterArtist flow -- a
 * "user"-role actor invoking a CRUD-delegated Flow (one bound via {@code input.mode} to a
 * non-admin-only concept's create/update/delete operation) got {@code flow.execute} from the
 * existing Flow-CRUD-wrapper alignment in {@link RuntimeApiEmitter#generatePermissionManifest}, but
 * the Flow's own {@code createConcept} step still 422d with {@code capability_auth} on its first
 * write: that step compiles to a {@code CapabilityCallStep} gated by the separate, coarser
 * {@code capability.invoke} permission, which nothing granted "user" for this scenario before this
 * fix. Same isolation technique as {@link RuntimeApiEmitterAppDeclaredRoleFlowExecuteTest}: one
 * concept whose create IS delegated to a Flow (must get both grants), one Flow that only shares the
 * model but is NOT CRUD-delegated (must get neither from this mechanism).
 */
class RuntimeApiEmitterCrudDelegatedFlowCapabilityInvokeTest {

    private static final String MODEL_JSON = """
            {
              "namespace": "wave3capinvoke",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Widget",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string", "required": true }
                  ]
                }
              ],
              "capabilities": [
                { "name": "persistence", "type": "PersistenceCapability", "operations": ["save", "findById"] }
              ],
              "bindings": [
                { "capability": "persistence", "adapter": "repository" },
                { "capability": "eventBus", "adapter": "inproc" }
              ],
              "flows": [
                {
                  "name": "RegisterWidget",
                  "input": { "concept": "Widget", "mode": "create" },
                  "steps": [
                    { "name": "save-widget", "type": "createConcept", "scope": "Widget", "input": "$input", "output": "$saved" },
                    { "name": "return-widget", "type": "return", "value": "$saved" }
                  ]
                },
                {
                  "name": "UnrelatedReport",
                  "input": { "concept": "Widget" },
                  "steps": [
                    { "name": "return-ok", "type": "return", "value": "true" }
                  ]
                }
              ]
            }
            """;

    @Test
    void crudDelegatedFlowGrantsUserBothFlowExecuteAndCapabilityInvoke_unrelatedFlowGetsNeitherFromThisPath() throws Exception {
        Path modelPath = Files.createTempFile("npdev-wave3-capinvoke-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-wave3-capinvoke-out-");
        Path migrations = Files.createTempDirectory("npdev-wave3-capinvoke-mig-");

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(SettingStore.builder().build()))
                .generate(compiled, out, migrations, modelPath);

        String manifest = Files.readString(
                out.resolve("src/main/resources/npdev/security/dev.permissions.json"));

        assertTrue(hasGrant(manifest, "flow.execute", "user"),
                "Widget's create is delegated to RegisterWidget -- \"user\" must be granted flow.execute: " + manifest);
        assertTrue(hasGrant(manifest, "capability.invoke", "user"),
                "RegisterWidget's own createConcept step needs capability.invoke, or a \"user\" actor "
                        + "passes flow.execute and then still 422s with capability_auth on the write: " + manifest);
    }

    /** Matches a single {@code {"permission": "...", ..., "role": "..."}} grant object, order-agnostic. */
    private static boolean hasGrant(String manifestJson, String permission, String role) {
        Pattern grantObject = Pattern.compile("\\{[^{}]*}");
        Matcher matcher = grantObject.matcher(manifestJson);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (candidate.contains("\"permission\": \"" + permission + "\"")
                    && candidate.toLowerCase(java.util.Locale.ROOT).contains("\"role\": \"" + role.toLowerCase(java.util.Locale.ROOT) + "\"")) {
                return true;
            }
        }
        return false;
    }
}
