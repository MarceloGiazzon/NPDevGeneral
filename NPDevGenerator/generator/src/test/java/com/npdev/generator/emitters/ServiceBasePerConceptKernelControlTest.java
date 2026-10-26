package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code crud.kernelControlled} is overridable at concept scope ({@code overrides: {"concept:X": ...}}),
 * and REG-44's {@code UnenforceableAccessRuleCheck} already resolved it per concept, but the service
 * emitter applied ONE app-level boolean to every concept. So a concept-level opt-out was ignored, and --
 * worse -- a concept-level {@code true} under an app-level {@code false} passed the REG-44 check yet
 * generated a service with no authorization calls at all: row-level access rules silently unenforced.
 * Each concept's generated service must follow ITS OWN resolved value.
 */
class ServiceBasePerConceptKernelControlTest {

    private static final String PERMISSION_CHECK = "checkCrudPermission";

    private static final String MODEL_JSON = """
            {
              "namespace": "perconceptkc",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Ticket",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "ownerId", "type": "string", "required": true }
                  ],
                  "access": { "write": "ownerId == $user.id" }
                },
                {
                  "name": "Note",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "body", "type": "string", "required": true }
                  ]
                }
              ]
            }
            """;

    @Test
    void aConceptOptOutUnderAnEnabledAppDropsTheChecksForThatConceptOnly() throws Exception {
        Map<String, String> services = generate(true, Map.of("Note", false));

        assertFalse(services.get("Note").contains(PERMISSION_CHECK),
                "concept:Note=false must drop its authorization calls");
        assertTrue(services.get("Ticket").contains(PERMISSION_CHECK),
                "Ticket has no override and must keep them");
    }

    @Test
    void aConceptOptInUnderADisabledAppKeepsTheChecksForThatConceptOnly() throws Exception {
        Map<String, String> services = generate(false, Map.of("Ticket", true));

        assertTrue(services.get("Ticket").contains(PERMISSION_CHECK),
                "concept:Ticket=true must keep its authorization calls even though the app default is false");
        assertFalse(services.get("Note").contains(PERMISSION_CHECK),
                "Note has no override and follows the app-level false");
    }

    @Test
    void withNoOverrideEveryConceptFollowsTheAppValue() throws Exception {
        Map<String, String> on = generate(true, Map.of());
        assertTrue(on.get("Ticket").contains(PERMISSION_CHECK) && on.get("Note").contains(PERMISSION_CHECK));
    }

    private static Map<String, String> generate(boolean appValue, Map<String, Boolean> conceptOverrides) throws Exception {
        Path modelPath = Files.createTempFile("npdev-perconceptkc-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));

        Path out = Files.createTempDirectory("npdev-perconceptkc-out-");
        Path migrations = Files.createTempDirectory("npdev-perconceptkc-mig-");

        SettingStore.Builder store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.CRUD_KERNEL_CONTROLLED.id(), appValue), "test");
        conceptOverrides.forEach((concept, value) -> store.layer(SettingScope.CONCEPT, "concept:" + concept,
                Map.of(NpdevSettings.CRUD_KERNEL_CONTROLLED.id(), value), "test"));

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(store.build()))
                .generate(compiled, out, migrations, modelPath);

        Path services = out.resolve("src/main/java/com/npdev/generated/services");
        return Map.of(
                "Ticket", Files.readString(services.resolve("TicketServiceBase.java")),
                "Note", Files.readString(services.resolve("NoteServiceBase.java")));
    }
}
