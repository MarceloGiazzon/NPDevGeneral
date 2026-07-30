package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3B / Gap 8): {@code aggregate.onValidate} runs a
 * declared procedure BEFORE the root upsert (and every recursive child upsert) -- a sibling of
 * {@code onCommit}, not a flag on it: onCommit runs AFTER the whole tree is written and relies on
 * G1's transaction to roll a failure back, while onValidate runs first so a rejection means no
 * write is ever attempted, with or without a transaction manager available at all. Deliberately
 * constructed WITHOUT a {@code PlatformTransactionManager} (the in-proc-only path) to prove this
 * directly: if onValidate ran AFTER the root write instead of before, this test would observe a
 * real half-written Movimento row with nothing to roll it back.
 */
class AggregateRuntimeOnValidateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Movimento", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "situacao", "type": "string" },
              { "name": "guardRef", "type": "uuid" } ] },
            { "name": "GuardCheck", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "checked", "type": "string" } ] }
          ],
          "aggregates": [
            { "name": "Movimento", "root": "Movimento", "onValidate": "ValidateMovimentoProcedure", "collections": [] }
          ],
          "procedures": [
            { "name": "ValidateMovimentoProcedure", "steps": [
              { "name": "check-guard", "type": "patchConcept", "concept": "GuardCheck", "id": "$guardRef",
                "set": { "checked": "true" }, "target": "checked" },
              { "name": "return-checked", "type": "return", "value": "$checked" }
            ] }
          ]
        }
        """;

    private ConceptGateway gateway;
    private CompiledModel model;
    private final ExecutionContext ctx = ExecutionContext.of("trial", "tester");

    @BeforeEach
    void setUp() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        model = new ModelCompiler().compile(ast);
        gateway = new DefaultConceptGateway(new InMemoryConceptStore());
    }

    /** Success path: onValidate's guard check passes, and the aggregate's own root commits. */
    @Test
    void onValidatePassesAndTheRootCommitsNormally() {
        gateway.save(new ConceptWriteRequest("GuardCheck", "G1", "trial",
                Map.of("id", "G1", "checked", "false")), ctx);
        ProcedureRunner procedureRunner = new ProcedureRunner(model, gateway, null, null);
        AggregateRuntime runtime = new AggregateRuntime(model, gateway, procedureRunner);

        Map<String, Object> draft = new LinkedHashMap<>(Map.of("id", "M1", "situacao", "Novo", "guardRef", "G1"));
        runtime.commit("Movimento", draft, ctx);

        assertEquals("Novo", gateway.read(new ConceptReadRequest("Movimento", "M1", null), ctx).get().data().get("situacao"),
                "onValidate passing must let the aggregate's own root commit");
        assertEquals("true", gateway.read(new ConceptReadRequest("GuardCheck", "G1", null), ctx).get().data().get("checked"),
                "onValidate's own patchConcept must have run");
    }

    /**
     * Failure path: onValidate's procedure fails (patchConcept targets a GuardCheck id that does
     * not exist) -- the aggregate's own root must NEVER be written at all, not merely rolled back.
     * No PlatformTransactionManager is supplied here on purpose: there is nothing to roll a write
     * back WITH, so a root record appearing after this call would prove onValidate ran too late
     * (or not at all), not that a rollback failed.
     */
    @Test
    void onValidateFailureMeansTheRootIsNeverWrittenAtAll() {
        ProcedureRunner procedureRunner = new ProcedureRunner(model, gateway, null, null);
        AggregateRuntime runtime = new AggregateRuntime(model, gateway, procedureRunner);

        Map<String, Object> draft = new LinkedHashMap<>(Map.of("id", "M1", "situacao", "Novo", "guardRef", "does-not-exist"));
        assertThrows(RuntimeException.class, () -> runtime.commit("Movimento", draft, ctx));

        assertEquals(Optional.empty(), gateway.read(new ConceptReadRequest("Movimento", "M1", null), ctx),
                "onValidate rejecting the draft must mean the root write never happens, with no transaction manager to undo it");
    }

    /**
     * onValidate runs before {@code idOrNew} resolves the root's id, so it must work correctly
     * even for a brand-new draft with no {@code id} at all -- unlike onCommit, which by definition
     * only ever sees the freshly reloaded, already-persisted (and so always-id-bearing) tree.
     */
    @Test
    void onValidateRunsBeforeIdAssignmentAndTheRootStillGetsANewGeneratedId() {
        gateway.save(new ConceptWriteRequest("GuardCheck", "G2", "trial",
                Map.of("id", "G2", "checked", "false")), ctx);
        ProcedureRunner procedureRunner = new ProcedureRunner(model, gateway, null, null);
        AggregateRuntime runtime = new AggregateRuntime(model, gateway, procedureRunner);

        Map<String, Object> draft = new LinkedHashMap<>(Map.of("situacao", "Novo", "guardRef", "G2"));
        Map<String, Object> reloaded = runtime.commit("Movimento", draft, ctx);

        assertTrue(reloaded.get("id") != null && !String.valueOf(reloaded.get("id")).isBlank(),
                "a brand-new draft with no id must still get a generated one after onValidate passes");
        assertEquals("true", gateway.read(new ConceptReadRequest("GuardCheck", "G2", null), ctx).get().data().get("checked"),
                "onValidate must have run (and successfully patched GuardCheck) even with no id yet on the draft");
    }
}
