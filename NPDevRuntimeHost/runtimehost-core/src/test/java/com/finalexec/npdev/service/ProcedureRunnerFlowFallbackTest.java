package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P3-T5c (stage 3): a caller that runs a name as a procedure (a panel action, an aggregate hook, a workbench
 * action) reaches the flow that replaced the procedure, and reads the answer in the procedure result shape.
 */
class ProcedureRunnerFlowFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "3.0.0", "namespace": "demo", "version": "1.0",
          "concepts": [ { "name": "Noop", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
          "flows": [
            { "name": "OnlyFlow", "concept": "Noop", "expose": "ui", "steps": [ { "name": "r", "type": "return", "value": "$input" } ] },
            { "name": "Both", "concept": "Noop", "expose": "ui", "steps": [ { "name": "r", "type": "return", "value": "$input" } ] }
          ]
        }
        """;

    private static ProcedureRunner runner() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(MODEL)));
        return new ProcedureRunner(compiled, new NoopGateway());
    }

    @Test
    void aNameOnlyAFlowHasRunsTheFlowAndAnswersInTheProcedureShape() throws Exception {
        ProcedureRunner runner = runner();
        AtomicReference<String> ran = new AtomicReference<>();
        runner.setFlowInvoker((flow, input, context) -> {
            ran.set(flow + ":" + input);
            return ExecutionResult.ok(flow, Map.of("echo", input), List.of());
        });

        assertTrue(runner.canRun("OnlyFlow"));
        ProcedureExecutionResult result = runner.execute("OnlyFlow", Map.of("id", "7"), ExecutionContext.anonymous());

        assertTrue(result.ok());
        assertEquals("OnlyFlow:{id=7}", ran.get());
        assertEquals(Map.of("echo", Map.of("id", "7")), result.state().get("return"));
    }

    @Test
    void aNameIsRunAsTheFlowOfThatName() throws Exception {
        ProcedureRunner runner = runner();
        runner.setFlowInvoker((flow, input, context) -> ExecutionResult.ok(flow, "from-flow", List.of()));
        ProcedureExecutionResult result = runner.execute("Both", Map.of(), ExecutionContext.anonymous());
        assertEquals("from-flow", result.state().get("return"));
    }

    @Test
    void withoutAnInvokerAFlowOnlyNameIsStillNotFound() throws Exception {
        ProcedureRunner runner = runner();
        assertFalse(runner.canRun("OnlyFlow"));
        assertThrows(IllegalArgumentException.class, () -> runner.execute("OnlyFlow", Map.of(), ExecutionContext.anonymous()));
        assertFalse(runner.canRun("Nope"));
    }

    @Test
    void aFailedFlowBecomesAFailedProcedureResultWithTheStepCode() throws Exception {
        ProcedureRunner runner = runner();
        runner.setFlowInvoker((flow, input, context) -> ExecutionResult.failed(
                flow, List.of(), List.of(), "CONCEPT_NOT_FOUND: Concept record not found: Ride:r-1"));
        ProcedureExecutionResult result = runner.execute("OnlyFlow", Map.of(), ExecutionContext.anonymous());
        assertFalse(result.ok());
        assertEquals("CONCEPT_NOT_FOUND", result.failureCode());
        assertEquals("Concept record not found: Ride:r-1", result.failureMessage());

        runner.setFlowInvoker((flow, input, context) -> ExecutionResult.failed(flow, List.of(), List.of(), "boom"));
        ProcedureExecutionResult plain = runner.execute("OnlyFlow", Map.of(), ExecutionContext.anonymous());
        assertEquals("FAILED", plain.failureCode());
        assertEquals("boom", plain.failureMessage());
    }

    private static final class NoopGateway implements ConceptGateway {
        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            return Optional.empty();
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            return List.of();
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }
    }
}
