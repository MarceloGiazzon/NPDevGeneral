package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-86, end to end through the REAL authoring pipeline (JsonModelParser -> ModelCompiler ->
 * ProcedureRunner), not just DefaultProcedureExecutor's own unit tests (which construct {@code
 * ProcedureStep} directly, bypassing {@code ProcedureRunner.toProcedureStep}'s own literal-vs-$ref
 * conversion -- the SECOND place this bug lived, since {@code refOf} force-stringified any value,
 * including a literal array/object, into unusable text like {@code "[a, b]"}).
 */
class ProcedureRunnerLiteralValueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Noop", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true } ] }
          ],
          "procedures": [
            { "name": "ReturnLiteralArray", "steps": [
              { "name": "map-summary", "type": "mapValue",
                "value": [ { "sku": "WIDGET-1", "status": "active" }, { "sku": "WIDGET-2", "status": "active" } ],
                "target": "summary" },
              { "name": "return-summary", "type": "return", "value": "$summary" }
            ] }
          ]
        }
        """;

    private static CompiledModel compiledModel() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        return new ModelCompiler().compile(ast);
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
    void aDslAuthoredLiteralArraySurvivesParseCompileAndExecuteWithNoCapabilityRoundTrip() throws Exception {
        CompiledModel model = compiledModel();
        ProcedureRunner runner = new ProcedureRunner(model, new NoopGateway(), null, null);

        var result = runner.execute("ReturnLiteralArray", Map.of(), ExecutionContext.anonymous());

        assertTrue(result.ok(), "procedure should succeed: " + result.failureCode() + " " + result.failureMessage());
        assertEquals(
                List.of(Map.of("sku", "WIDGET-1", "status", "active"), Map.of("sku", "WIDGET-2", "status", "active")),
                result.state().get("return")
        );
    }
}
