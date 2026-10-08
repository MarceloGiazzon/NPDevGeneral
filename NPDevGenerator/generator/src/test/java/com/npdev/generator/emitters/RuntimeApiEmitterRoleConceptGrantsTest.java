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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roles and users: {@code roles[].concepts} drives the static CRUD permission manifest. A concept
 * named by any role is granted only to the roles naming it, per operation ({@code read} covers
 * list); the built-in "user" role loses it. A concept no role names stays open to "user" AND to
 * every declared role, so a login holding only an app role needs no extra USER row.
 */
class RuntimeApiEmitterRoleConceptGrantsTest {

    private static final String MODEL_JSON = """
            {
              "namespace": "roleconcepts",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                { "name": "Widget", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] },
                { "name": "Mosaic", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
              ],
              "capabilities": [
                { "name": "persistence", "type": "PersistenceCapability", "operations": ["save", "findById"] }
              ],
              "bindings": [
                { "capability": "persistence", "adapter": "repository" },
                { "capability": "eventBus", "adapter": "inproc" }
              ],
              "roles": [
                { "name": "Member", "grants": ["EXECUTE_FLOW"], "concepts": { "Mosaic": ["read", "create", "update"] } },
                { "name": "Visitor", "concepts": { "Mosaic": ["read"] } },
                { "name": "Auditor", "grants": ["READ_EXECUTIONS"] }
              ]
            }
            """;

    private static String manifest;

    @BeforeAll
    static void generate() throws Exception {
        Path modelPath = Files.createTempFile("npdev-roleconcepts-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-roleconcepts-out-");
        Path migrations = Files.createTempDirectory("npdev-roleconcepts-mig-");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(SettingStore.builder().build()))
                .generate(compiled, out, migrations, modelPath);
        manifest = Files.readString(out.resolve("src/main/resources/npdev/security/dev.permissions.json"));
    }

    @Test
    void aRestrictedConceptIsGrantedPerOperationToTheNamingRolesOnly() {
        assertTrue(hasGrant("create:mosaic", "member"), manifest);
        assertTrue(hasGrant("update:mosaic", "member"), manifest);
        assertTrue(hasGrant("read:mosaic", "visitor"), manifest);
        assertTrue(hasGrant("list:mosaic", "visitor"), "read must also cover list: " + manifest);

        assertFalse(hasGrant("create:mosaic", "visitor"), "Visitor may only read: " + manifest);
        assertFalse(hasGrant("delete:mosaic", "member"), "Member never declared delete: " + manifest);
        assertFalse(hasGrant("read:mosaic", "user"), "the built-in user role loses a restricted concept: " + manifest);
        assertFalse(hasGrant("read:mosaic", "auditor"), "a role not naming the concept gets nothing: " + manifest);
    }

    @Test
    void anUnrestrictedConceptStaysOpenToUserAndEveryDeclaredRole() {
        for (String role : new String[]{"user", "member", "visitor", "auditor"}) {
            assertTrue(hasGrant("create:widget", role), role + ": " + manifest);
            assertTrue(hasGrant("list:widget", role), role + ": " + manifest);
        }
        assertTrue(hasGrant("event.publish", "member"),
                "a declared role that may write must clear the mutation-event gate too: " + manifest);
    }

    private static boolean hasGrant(String permission, String role) {
        Matcher matcher = Pattern.compile("\\{[^{}]*}").matcher(manifest);
        while (matcher.find()) {
            String candidate = matcher.group().toLowerCase(Locale.ROOT);
            if (candidate.contains("\"permission\": \"" + permission + "\"")
                    && candidate.contains("\"role\": \"" + role + "\"")) {
                return true;
            }
        }
        return false;
    }
}
