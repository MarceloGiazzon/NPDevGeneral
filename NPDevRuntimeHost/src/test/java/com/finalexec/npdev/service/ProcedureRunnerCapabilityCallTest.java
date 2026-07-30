package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.CapabilityDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-73: a procedure's {@code callCapability} step never resolved an adapter from the model's
 * {@code bindings} list -- {@code ProcedureRunner.toProcedureStep} hardcoded adapterId to "",
 * so every procedure-side capability call reached the dispatcher with a null adapterId and failed
 * CAPABILITY_BINDING_MISSING even with a real binding declared. The flow path
 * ({@code CompiledModelFlowDefinitionProvider}) already resolved this from bindings; the procedure
 * path (used by both panel-action procedure bindings and AggregateRuntime.invoke()) never did.
 * Found live while wiring the Movimento aggregate's Sugerir* procedures (Move 3 G2).
 */
class ProcedureRunnerCapabilityCallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Noop", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true } ] }
          ],
          "customCapabilities": [
            { "name": "greeting", "type": "GreetingCapability", "operations": ["sayHello"] }
          ],
          "bindings": [
            { "capability": "greeting", "adapter": "test-adapter" }
          ],
          "procedures": [
            { "name": "Greet", "steps": [
              { "name": "greet-step", "type": "capabilityCall", "capability": "greeting",
                "operation": "sayHello", "args": { "input": "$input" }, "target": "resultado" },
              { "name": "return-resultado", "type": "return", "value": "$resultado" }
            ] }
          ]
        }
        """;

    private static CompiledModel compiledModel() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        return new ModelCompiler().compile(ast);
    }

    /** Records the CapabilityCall it received and always succeeds -- lets the test assert on adapterId. */
    private static final class RecordingDispatcher implements CapabilityDispatcher {
        CapabilityCall lastCall;

        @Override
        public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
            lastCall = call;
            return CapabilityResult.success(Map.of("greeting", "hi"));
        }
    }

    private static final class NoopGateway implements ConceptGateway {
        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest r, ExecutionContext c) { return Optional.empty(); }
        @Override
        public List<ConceptRecord> list(ConceptListRequest r, ExecutionContext c) { return List.of(); }
        @Override
        public ConceptRecord save(ConceptWriteRequest r, ExecutionContext c) {
            throw new UnsupportedOperationException();
        }
        @Override
        public void delete(ConceptReadRequest r, ExecutionContext c) { throw new UnsupportedOperationException(); }
    }

    @Test
    void callCapabilityStepResolvesAdapterFromModelBindings() throws Exception {
        CompiledModel model = compiledModel();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ProcedureRunner runner = new ProcedureRunner(model, new NoopGateway(), dispatcher, null);

        var result = runner.execute("Greet", Map.of("input", "world"), ExecutionContext.anonymous());

        assertNotNull(dispatcher.lastCall, "dispatcher should have been invoked");
        assertEquals("test-adapter", dispatcher.lastCall.adapterId(),
                "adapterId must come from the model's bindings list, not be left null/blank");
        assertTrue(result.ok(), "procedure should succeed once the dispatcher can resolve a real adapter: "
                + result.failureCode() + " " + result.failureMessage());
    }
}
