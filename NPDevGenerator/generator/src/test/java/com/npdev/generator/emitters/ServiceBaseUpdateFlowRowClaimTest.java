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
 * REG-308 residue: an update flow can move the row's row_version, so the generated update drops the
 * request's version before the final save. Where the concept gateway write sits in front of the flow
 * ({@code crud.kernelControlled: true}) that write is the compare-and-swap; with kernel control off nothing
 * was, and two same-version PUTs interleaving between the Java check and the flow both wrote. The service
 * must claim the row with {@code claimRowForUpdate} BEFORE the flow, and only in that shape.
 *
 * <p>Asserted against real generator output rather than a booted app: {@code crud.kernelControlled} is an
 * app-wide setting, and no corpus sample runs with it off (it would switch off every permission probe).
 */
class ServiceBaseUpdateFlowRowClaimTest {

    private static final String CLAIM = "GeneratedCrudRuntimeSupport.claimRowForUpdate(\"Ticket\"";
    private static final String FLOW = "enforceWithUpdateFlow(crudCtx, id, updatePayload);";

    private static final String MODEL_JSON = """
            {
              "namespace": "reg308claim",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Ticket",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "subject", "type": "string", "required": true }
                  ]
                },
                {
                  "name": "Note",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "body", "type": "string", "required": true }
                  ]
                }
              ],
              "capabilities": [
                { "name": "persistence", "type": "PersistenceCapability", "operations": ["save", "unique", "findById"] }
              ],
              "bindings": [
                { "capability": "persistence", "adapter": "repository" },
                { "capability": "eventBus", "adapter": "inproc" }
              ],
              "flows": [
                {
                  "name": "UpdateTicket",
                  "input": { "concept": "Ticket", "mode": "update" },
                  "steps": [
                    { "name": "save-ticket", "type": "capabilityCall", "capability": "persistence", "operation": "save",
                      "args": ["$input"], "output": "$saved" },
                    { "name": "return-ticket", "type": "return", "value": "$saved" }
                  ]
                }
              ]
            }
            """;

    @Test
    void anUpdateFlowWithoutKernelControlClaimsTheRowBeforeTheFlowRuns() throws Exception {
        String ticket = generate(false, "Ticket");

        int claim = ticket.indexOf(CLAIM);
        int flow = ticket.indexOf(FLOW);
        assertTrue(claim >= 0 && flow >= 0 && claim < flow,
                "the row must be claimed (compare-and-swap) BEFORE the update flow runs: " + ticket);
    }

    @Test
    void kernelControlIsSettledByTheGatewayWriteSoThereIsNoSecondClaim() throws Exception {
        assertFalse(generate(true, "Ticket").contains(CLAIM));
    }

    @Test
    void aConceptWithoutAnUpdateFlowNeedsNoClaim() throws Exception {
        assertFalse(generate(false, "Note").contains("claimRowForUpdate(\"Note\""));
    }

    private static String generate(boolean kernelControlled, String concept) throws Exception {
        Path modelPath = Files.createTempFile("npdev-reg308claim-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));

        Path out = Files.createTempDirectory("npdev-reg308claim-out-");
        Path migrations = Files.createTempDirectory("npdev-reg308claim-mig-");

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(SettingStore.builder()
                        .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                                Map.of(NpdevSettings.CRUD_KERNEL_CONTROLLED.id(), kernelControlled), "test")
                        .build()))
                .generate(compiled, out, migrations, modelPath);

        return Files.readString(out.resolve("src/main/java/com/npdev/generated/services/" + concept + "ServiceBase.java"));
    }
}
