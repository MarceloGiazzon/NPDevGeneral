package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** REG-65 / F4 (docs/FINAL_OPEN_ITEMS_PLAN.md): generatedAction is a canonical flowStep.type value
 * (DSL 2.0 sugar for CAPABILITY_CALL, docs/FLOWS.md §3) that FlowValidation always rejected as
 * "unsupported step type" -- despite the parser, compiler, and generator/runtime all already
 * supporting it. This is the regression proof: before the fix, {@code validateModelValidates}
 * failed with exactly that message. */
class GeneratedActionFlowStepValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String flowStepsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.genaction", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "total", "type": "integer" } ] }
              ],
              "flows": [
                { "name": "ProcessOrder", "concept": "Order", "steps": %s }
              ]
            }
            """.formatted(flowStepsJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void generatedActionStepValidatesCleanAndCompilesToACapabilityCall() throws Exception {
        String json = modelJson("""
            [
              { "name": "score-order", "type": "generatedAction", "actionName": "ScoreOrderRisk",
                "input": "input", "output": "scored" }
            ]
            """);
        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);
        assertFalse(errors.stream().anyMatch(e -> e.contains("unsupported step type")),
                "generatedAction must not be rejected as unsupported: " + errors);

        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.getFlows().stream().findFirst().orElseThrow();
        CompiledFlowStep step = flow.getSteps().get(0);
        assertEquals("generatedAction", step.getType());
        assertEquals("ScoreOrderRisk", step.getGeneratedActionName());
        assertNotNull(step.getCapabilityCall(), "generatedAction must desugar to a capability call");
        assertEquals("GeneratedActionCapability", step.getCapabilityCall().getCapabilityType());
    }

    @Test
    void missingActionNameIsRejectedAtSchemaLevel() {
        // The schema's own required:["actionName"] (model.schema.json $defs.flowStep.allOf) fires
        // before JsonModelParser's post-parse actionName check (:1482-1484) ever runs -- that check
        // is defensive-in-depth for a non-schema-validated construction path, not the live guard.
        assertThrows(ModelSchemaValidationException.class, () -> new JsonModelParser().parse(
                MAPPER.readTree(modelJson("""
                    [ { "name": "score-order", "type": "generatedAction" } ]
                    """))
        ));
    }
}
