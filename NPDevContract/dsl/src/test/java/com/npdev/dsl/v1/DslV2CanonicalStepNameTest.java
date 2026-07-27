package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.A.2 (docs/DSL2_AND_DECOMPOSITION_PLAN.md): the DSL 2.0 canonical step-type spellings
 * ({@code invariantCheck}, {@code map}) must parse, validate, and compile to the exact same
 * compiled-model shape as the v1 spellings they're introduced alongside ({@code validate},
 * {@code assign}) -- this is the widening half of the migration: both spellings are valid today,
 * and must mean the same thing. The other 10 canonical names ({@code capabilityCall},
 * {@code emitEvent}, {@code scheduleEvent}, {@code branch}, {@code awaitEvent}, {@code return},
 * {@code forEach}, {@code createConcept}, {@code updateConcept}, {@code generatedAction}) were
 * already valid schema values before this change, so they need no new coverage here.
 */
class DslV2CanonicalStepNameTest {

    @Test
    void invariantCheckStepCompilesIdenticallyToValidateStep() throws Exception {
        CompiledModel v1 = compile(flowModelWithStepType("validate"));
        CompiledModel v2 = compile(flowModelWithStepType("invariantCheck"));

        assertEquals(canonicalStepTypes(v1), canonicalStepTypes(v2));
        assertEquals("invariant", canonicalStepTypes(v2).get(0));
    }

    @Test
    void mapStepCompilesIdenticallyToAssignStep() throws Exception {
        CompiledModel v1 = compile(mapModelWithStepType("assign"));
        CompiledModel v2 = compile(mapModelWithStepType("map"));

        assertEquals(canonicalStepTypes(v1), canonicalStepTypes(v2));
        assertEquals("map", canonicalStepTypes(v2).get(0));
    }

    private static List<String> canonicalStepTypes(CompiledModel model) {
        CompiledFlow flow = model.findFlow("DemoFlow").orElseThrow();
        return flow.getSteps().stream().map(step -> step.getType()).toList();
    }

    private static CompiledModel compile(String json) throws Exception {
        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-dsl-v2-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }

    private static String flowModelWithStepType(String stepType) {
        return """
                {
                  "namespace": "dslv2.invariantcheck",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "label", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "name": "LabelRequired", "expr": "label != null && label != ''" }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "DemoFlow",
                      "input": { "concept": "Widget", "mode": "create" },
                      "steps": [
                        { "name": "check-label", "type": "%s", "scope": "Widget", "invariants": ["LabelRequired"] },
                        { "name": "return-input", "type": "return", "value": "$input" }
                      ]
                    }
                  ]
                }
                """.formatted(stepType);
    }

    private static String mapModelWithStepType(String stepType) {
        return """
                {
                  "namespace": "dslv2.map",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "label", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "DemoFlow",
                      "input": { "concept": "Widget", "mode": "create" },
                      "steps": [
                        { "name": "capture-label", "type": "%s", "input": "$input.label", "out": "$capturedLabel" },
                        { "name": "return-captured", "type": "return", "value": "$capturedLabel" }
                      ]
                    }
                  ]
                }
                """.formatted(stepType);
    }
}
