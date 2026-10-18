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
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "concepts": [ { "name": "Noop", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "capabilities": [
                { "name": "notification", "type": "NotificationCapability",
                  "operations": [ { "name": "send", "sideEffects": "external", "policy": %s } ] }
              ],
              "bindings": [ { "capability": "notification", "adapter": "test-adapter" } ],
              "procedures": [
                { "name": "Notify", "steps": [
                  { "name": "send-step", "type": "capabilityCall", "capability": "notification",
                    "operation": "send", "args": { "input": "$input" }, "target": "sent" },
                  { "name": "return-sent", "type": "return", "value": "$sent" }
                ] }
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
        var result = runner.execute("Notify", Map.of("input", "x"), ExecutionContext.anonymous());

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

        var result = runner.execute("Notify", Map.of("input", "x"), ExecutionContext.anonymous());

        assertTrue(result.ok(), result.failureCode() + " " + result.failureMessage());
        assertEquals(3, calls.get());
    }
}
