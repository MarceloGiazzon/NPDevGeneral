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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * {@code {{^hasCreateFlow}}}, never alongside {@code enforceWithCreateFlow}. GREEN after: the
 * enforcement now always runs BEFORE the flow (unconditional on {@code kernelControlled}), so a
 * denied row-level write throws before the flow's own side effects (notifications, external calls)
 * ever run. This test asserts the STRUCTURAL fix (the enforcement call is present and precedes the
 * flow call) directly against real generator output -- {@code service-base.mustache}'s golden-demo
 * generator test module had zero coverage of the create/update/delete-flow branches at all before
 * this (the same blind spot that let the read-side twin of this bug go undetected until live E2E
 * testing, per docs/ROW_LEVEL_AUTHORIZATION.md's own history).
 *
 * <p>REG-120 (2026-08-02) changed WHICH method the flow-backed create path calls for this
 * enforcement: {@code authorizeCreateFlowWithConceptGateway} (validate-only, never persists) instead of
 * {@code enforceWithConceptGateway} (which also persists, and raced the flow's own separate write --
 * see {@link #flowBackedCreateNeverWritesTheRowASecondTimeThroughSaveWithIntegrityMapping}). The
 * enforcement guarantee this test asserts (runs, and runs before the flow) is unchanged; only the
 * call site's name and its no-longer-persisting behavior are.
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
                    { "name": "save-ticket", "type": "capabilityCall", "capability": "persistence", "operation": "save",
                      "args": ["$input"], "output": "$saved" },
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

        assertTrue(generated.contains("authorizeCreateFlowWithConceptGateway(\"Ticket\", generatedId, createPayload);"),
                "the row-level/semantic gateway check must still be emitted for a flow-backed concept "
                        + "(REG-120: as the validate-only authorizeCreateFlowWithConceptGateway, not the "
                        + "persisting enforceWithConceptGateway): " + generated);
        assertTrue(generated.contains("enforceWithCreateFlow(crudCtx, generatedId, createPayload);"),
                "the create flow must still run: " + generated);

        int gatewayCallIndex = generated.indexOf("authorizeCreateFlowWithConceptGateway(\"Ticket\", generatedId, createPayload);");
        int flowCallIndex = generated.indexOf("enforceWithCreateFlow(crudCtx, generatedId, createPayload);");
        assertTrue(gatewayCallIndex >= 0 && flowCallIndex >= 0 && gatewayCallIndex < flowCallIndex,
                "the row-level/semantic gateway check must run BEFORE the create flow's own side effects, "
                        + "not be skipped in its presence: " + generated);
    }

    /**
     * REG-120: a concept whose create is delegated to a declared Flow got DOUBLE-PERSISTED on every
     * create -- the Flow's own {@code createConcept} step already writes the row through the kernel's
     * persistence capability, then {@code createFromSource} unconditionally called
     * {@code saveWithIntegrityMapping(e)} on a freshly-constructed entity carrying the SAME id,
     * writing the identical row again through a completely separate persistence mechanism. Live-
     * reproduced (2026-08-02) via {@code POST /api/concepts/canary_tasks} against npdev-canary's own
     * CanaryTask (which declares exactly this shape) on a fresh boot: HTTP 500, matching the original
     * finding's H2 "Concurrent update" error.
     *
     * <p>This test asserts the STRUCTURAL fix directly against real generator output: a Flow-backed
     * create must never re-construct-and-save a second entity -- it must fetch what the Flow already
     * wrote instead.
     */
    @Test
    void flowBackedCreateNeverWritesTheRowASecondTimeThroughSaveWithIntegrityMapping() throws Exception {
        Path modelPath = Files.createTempFile("npdev-reg120-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-reg120-out-");
        Path migrations = Files.createTempDirectory("npdev-reg120-mig-");

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(SettingStore.builder().build()))
                .generate(compiled, out, migrations, modelPath);

        String generated = Files.readString(out.resolve("src/main/java/com/npdev/generated/services/TicketServiceBase.java"));

        assertTrue(generated.contains("enforceWithCreateFlow(crudCtx, generatedId, createPayload);"),
                "sanity check -- the create flow must still run: " + generated);
        assertFalse(generated.contains("= saveWithIntegrityMapping(e)"),
                "a Flow-backed create must never ALSO construct a fresh entity and save it again -- "
                        + "that is the exact double-persist REG-120 reported (the flow's own write is "
                        + "authoritative): " + generated);
        assertTrue(generated.contains("persistence.findById(generatedId)"),
                "a Flow-backed create must fetch the row the flow already persisted, not re-write it: "
                        + generated);
    }
}
