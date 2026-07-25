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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-16-resid Round 2 (LNCH-13 adversarial review, 2026-07-25) found that a concept declaring a
 * custom create/update/delete Flow got ZERO row-level {@code access.write} enforcement on its
 * generated REST endpoint: {@code enforceWithCreateFlow}/{@code enforceWithUpdateFlow}/
 * {@code enforceWithDeleteFlow} only ran {@code kernelRunner.execute(...)}, never
 * {@code conceptGateway.save/delete(...)} -- and persistence afterward went straight through
 * {@code conceptStore}, bypassing {@code ConceptGatewaySemanticPolicy.isRowWritable} entirely. This
 * completely defeated LNCH-13's write-scoping guarantee for any concept combining a custom flow with
 * an {@code access.write} rule -- a real-world-plausible combination.
 *
 * <p>RED before the fix: {@code enforceWithConceptGateway} was only emitted inside
 * {@code {{^hasCreateFlow}}}, never alongside {@code enforceWithCreateFlow}. GREEN after: it is now
 * unconditional on {@code kernelControlled} and always runs BEFORE the flow, so a denied row-level
 * write throws before the flow's own side effects (notifications, external calls) ever run. This test
 * asserts the STRUCTURAL fix (the enforcement call is present and precedes the flow call) directly
 * against real generator output -- {@code service-base.mustache}'s golden-demo generator test module
 * had zero coverage of the create/update/delete-flow branches at all before this (the same blind spot
 * that let the read-side twin of this bug go undetected until live E2E testing, per
 * docs/ROW_LEVEL_AUTHORIZATION.md's own history).
 */
class ServiceBaseFlowRowLevelAuthzTest {

    private static final String MODEL_JSON = """
            {
              "namespace": "reg16resid",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Ticket",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "ownerId", "type": "string", "required": true },
                    { "name": "subject", "type": "string", "required": true }
                  ],
                  "access": {
                    "write": "ownerId == $user.id"
                  }
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
                  "name": "CreateTicket",
                  "input": { "concept": "Ticket", "mode": "create" },
                  "steps": [
                    { "name": "save-ticket", "type": "capabilityCall", "cap": "persistence", "op": "save",
                      "args": ["$input"], "out": "$saved" },
                    { "name": "return-ticket", "type": "return", "value": "$saved" }
                  ]
                }
              ]
            }
            """;

    @Test
    void flowBackedCreateEnforcesRowLevelWriteAccessBeforeTheFlowRuns() throws Exception {
        Path modelPath = Files.createTempFile("npdev-reg16resid-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-reg16resid-out-");
        Path migrations = Files.createTempDirectory("npdev-reg16resid-mig-");

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(SettingStore.builder().build()))
                .generate(compiled, out, migrations, modelPath);

        String generated = Files.readString(out.resolve("src/main/java/com/npdev/generated/services/TicketServiceBase.java"));

        assertTrue(generated.contains("enforceWithConceptGateway(\"Ticket\", generatedId, createPayload);"),
                "the row-level/semantic gateway check must still be emitted for a flow-backed concept: " + generated);
        assertTrue(generated.contains("enforceWithCreateFlow(crudCtx, generatedId, createPayload);"),
                "the create flow must still run: " + generated);

        int gatewayCallIndex = generated.indexOf("enforceWithConceptGateway(\"Ticket\", generatedId, createPayload);");
        int flowCallIndex = generated.indexOf("enforceWithCreateFlow(crudCtx, generatedId, createPayload);");
        assertTrue(gatewayCallIndex >= 0 && flowCallIndex >= 0 && gatewayCallIndex < flowCallIndex,
                "the row-level/semantic gateway check must run BEFORE the create flow's own side effects, "
                        + "not be skipped in its presence: " + generated);
    }
}
