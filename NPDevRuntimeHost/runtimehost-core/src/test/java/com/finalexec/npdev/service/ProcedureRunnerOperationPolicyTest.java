package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-1: a procedure's callCapability step obeys the policy its capability operation declares in the
 * model -- the same deadline, retry and circuit a flow capabilityCall has -- with no DSL change.
 */
class ProcedureRunnerOperationPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String policy) {
        return """
            {
              "dslVersion": "3.0.0", "namespace": "wms", "version": "1.0",
              "concepts": [ { "name": "Noop", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "capabilities": [
                { "name": "notification", "type": "NotificationCapability", "adapter": "test-adapter",
                  "operations": [ { "name": "send", "sideEffects": "external", "policy": %s } ] }
              ]
            }
            """.formatted(policy);
    }

    private static ProcedureRunner runner(String policy, com.npdev.kernel.ports.CapabilityDispatcher dispatcher)
            throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(model(policy))));
        return new ProcedureRunner(compiled, new NoopGateway(), dispatcher, null);
    }

    private static final class NoopGateway implements ConceptGateway {
        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest r, ExecutionContext c) { return Optional.empty(); }
        @Override
        public List<ConceptRecord> list(ConceptListRequest r, ExecutionContext c) { return List.of(); }
        @Override
        public ConceptRecord save(ConceptWriteRequest r, ExecutionContext c) { throw new UnsupportedOperationException(); }
        @Override
        public void delete(ConceptReadRequest r, ExecutionContext c) { throw new UnsupportedOperationException(); }
    }

    /** The one capability call the policy applies to, as the one-step definition a flow's step is carried as. */
    private static com.npdev.kernel.procedures.ProcedureDefinition notifyStep() {
        return new com.npdev.kernel.procedures.ProcedureDefinition("flow:Notify:send-step", List.of(
                com.npdev.kernel.procedures.ProcedureStep.callCapability(
                        "send-step", "notification", "", "test-adapter", "send", List.of("input"), "sent")));
    }

    @Test
    void aSlowCapabilityTimesOutAtTheOperationsDeclaredDeadline() throws Exception {
        ProcedureRunner runner = runner("{ \"timeoutMs\": 200 }", (call, state) -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.success("late");
        });

        long started = System.currentTimeMillis();
        var result = runner.execute(notifyStep(), Map.of("input", "x"), ExecutionContext.anonymous());

        assertFalse(result.ok());
        assertEquals("CAPABILITY_TIMEOUT", result.failureCode());
        assertTrue(System.currentTimeMillis() - started < 2_000, "must not wait for the slow capability");
    }

    @Test
    void aTransientFailureIsRetriedPerTheDeclaredRetryCount() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ProcedureRunner runner = runner("{ \"retryCount\": 3, \"retryDelayMs\": 1 }", (CapabilityCall call, Map<String, Object> state) ->
                calls.incrementAndGet() < 3
                        ? CapabilityResult.failure("BLIP", "blip", CapabilityErrorKind.TRANSIENT, Map.of())
                        : CapabilityResult.success(Map.of("sent", true)));

        var result = runner.execute(notifyStep(), Map.of("input", "x"), ExecutionContext.anonymous());

        assertTrue(result.ok(), result.failureCode() + " " + result.failureMessage());
        assertEquals(3, calls.get());
    }

    /** FORM P3-T5b: a flow's concept step is a one-step definition the model does not declare; the host runs it as supplied. */
    @Test
    void aSuppliedOneStepDefinitionRunsEvenThoughTheModelDoesNotDeclareIt() throws Exception {
        ProcedureRunner runner = runner("{ \"timeoutMs\": 200 }", (call, state) -> CapabilityResult.success(null));
        com.npdev.kernel.procedures.ProcedureDefinition oneStep = new com.npdev.kernel.procedures.ProcedureDefinition(
                "flow:Anything:list", List.of(com.npdev.kernel.procedures.ProcedureStep.listConcepts("list", "Noop", "all")));

        com.npdev.kernel.procedures.ProcedureExecutionResult result = runner.execute(oneStep, Map.of(), ExecutionContext.anonymous());

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(List.of(), result.state().get("all"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> runner.execute("flow:Anything:list", Map.of(), ExecutionContext.anonymous()),
                "by name, an undeclared procedure is still refused");
    }
}
