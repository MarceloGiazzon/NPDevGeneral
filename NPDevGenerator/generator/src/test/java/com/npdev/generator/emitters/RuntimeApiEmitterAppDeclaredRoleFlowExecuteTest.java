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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-119: an app-declared role (RC-B1 {@code roles[]}/{@code grants[]}) holding {@code EXECUTE_FLOW}
 * could never reach the generated {@code POST /api/flows/{name}/execute} endpoint unless the actor
 * ALSO independently held the built-in "user" role or the configured super-user role.
 * {@link RuntimeApiEmitter#generatePermissionManifest} builds a STATIC manifest (consumed at runtime
 * by {@code StaticPermissionEvaluator}, a gate that runs BEFORE the kernel-level
 * {@code DefaultExecutionAuthorizationPolicy}/{@code RolePermissions} check that RC-B1 itself extends
 * and that DOES already honor app-declared roles) that, before this fix, granted "flow.execute" only
 * to the hardcoded role keys "user" and the configured super-user role -- never to any role read from
 * {@code model.getRoles()} -- so an app-declared role's EXECUTE_FLOW grant had no effect on this
 * specific endpoint, regardless of what the role declared.
 *
 * <p>The flow used here ({@code RecalculateWidgetSummary}) deliberately declares NO
 * create/update/delete {@code input.mode}, so it is never picked up by the OTHER (pre-existing)
 * mechanism that can grant "user" role flow.execute -- the Flow-CRUD-wrapper alignment in
 * {@code generatePermissionManifest}'s per-concept loop (see its own comment, "a user granted the
 * CRUD permission above must also be granted flow.execute"). This isolates the assertion to the new
 * app-declared-role code path, not that unrelated pre-existing mechanism.
 */
class RuntimeApiEmitterAppDeclaredRoleFlowExecuteTest {

    private static final String MODEL_JSON = """
            {
              "namespace": "reg119",
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
              "roles": [
                { "name": "WarehouseManager", "grants": ["EXECUTE_FLOW", "READ_EXECUTIONS"] },
                { "name": "ReadOnlyViewer", "grants": ["READ_EXECUTIONS"] }
              ],
              "flows": [
                {
                  "name": "RecalculateWidgetSummary",
                  "input": { "concept": "Widget" },
                  "steps": [
                    { "name": "return-ok", "type": "return", "value": "true" }
                  ]
                }
              ]
            }
            """;

    @Test
    void appDeclaredRoleWithExecuteFlowGrantGetsFlowExecuteInStaticManifest_roleWithoutItDoesNot() throws Exception {
        Path modelPath = Files.createTempFile("npdev-reg119-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-reg119-out-");
        Path migrations = Files.createTempDirectory("npdev-reg119-mig-");

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(SettingStore.builder().build()))
                .generate(compiled, out, migrations, modelPath);

        String manifest = Files.readString(
                out.resolve("src/main/resources/npdev/security/dev.permissions.json"));

        assertTrue(hasGrant(manifest, "flow.execute", "WarehouseManager"),
                "WarehouseManager declares EXECUTE_FLOW -- it must be granted \"flow.execute\" in the "
                        + "static manifest that gates POST /api/flows/{name}/execute, or it can never reach "
                        + "that endpoint despite the kernel-level RolePermissions check already honoring its "
                        + "declared grants: " + manifest);

        assertFalse(hasGrant(manifest, "flow.execute", "ReadOnlyViewer"),
                "ReadOnlyViewer never declared EXECUTE_FLOW -- granting it \"flow.execute\" anyway would "
                        + "trade a false deny for a false allow: " + manifest);
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
