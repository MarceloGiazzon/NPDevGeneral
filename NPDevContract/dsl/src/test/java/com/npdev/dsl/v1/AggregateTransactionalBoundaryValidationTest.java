package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6.1 (docs/NEXT_EXECUTION_PLAN.md 3.7): DDD's one-aggregate-one-transaction rule, enforced in
 * {@code FlowValidation#validateAggregateTransactionalBoundary}. A flow that writes concept-mutation
 * steps ({@code createConcept}/{@code updateConcept}/{@code createEntity}/{@code updateEntity})
 * belonging to two DIFFERENT aggregates' owned concepts must fail; writing within one aggregate's
 * boundary (or to a concept no aggregate owns at all) must not.
 */
class AggregateTransactionalBoundaryValidationTest {

    private static List<String> validate(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-agg-boundary-", ".json");
        Files.writeString(modelPath, json);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new SemanticValidator().validate(ast);
    }

    private static final String CONCEPTS = """
            "concepts": [
              { "name": "Order", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "total", "type": "integer" } ] },
              { "name": "Customer", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "name", "type": "string" } ] }
            ],
            "aggregates": [
              { "name": "OrderAggregate", "root": "Order" },
              { "name": "CustomerAggregate", "root": "Customer" }
            ],
            """;

    private static String flowStep(String name, String scope) {
        return """
                { "name": "%s", "type": "createConcept", "scope": "%s", "input": "$input", "output": "$saved" }
                """.formatted(name, scope);
    }

    @Test
    void flowWritingTwoDifferentAggregateRootsFails() throws Exception {
        String json = """
                {
                  "namespace": "agg.boundary.demo", "dslVersion": "1.0.0", "version": "v1",
                  %s
                  "flows": [ {
                    "name": "BadFlow",
                    "input": { "concept": "Order", "mode": "create" },
                    "steps": [ %s, %s ]
                  } ]
                }
                """.formatted(CONCEPTS, flowStep("save-order", "Order"), flowStep("save-customer", "Customer"));

        List<String> errors = validate(json);

        assertTrue(errors.stream().anyMatch(e -> e.contains("BadFlow")
                        && e.contains("OrderAggregate") && e.contains("CustomerAggregate")),
                "Expected an aggregate-boundary violation naming both aggregates, got: " + errors);
    }

    @Test
    void flowWritingOnlyOneAggregatesConceptsPasses() throws Exception {
        String json = """
                {
                  "namespace": "agg.boundary.demo", "dslVersion": "1.0.0", "version": "v1",
                  %s
                  "flows": [ {
                    "name": "GoodFlow",
                    "input": { "concept": "Order", "mode": "create" },
                    "steps": [ %s ]
                  } ]
                }
                """.formatted(CONCEPTS, flowStep("save-order", "Order"));

        List<String> errors = validate(json);

        assertFalse(errors.stream().anyMatch(e -> e.contains("aggregate")),
                "Expected no aggregate-boundary error for a single-aggregate flow, got: " + errors);
    }

    @Test
    void flowWritingAConceptNoAggregateOwnsIsUnaffected() throws Exception {
        String json = """
                {
                  "namespace": "agg.boundary.demo", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "AuditLog", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ],
                  "aggregates": [ { "name": "OrderAggregate", "root": "Order" } ],
                  "flows": [ {
                    "name": "GoodFlow",
                    "input": { "concept": "Order", "mode": "create" },
                    "steps": [ %s, %s ]
                  } ]
                }
                """.formatted(flowStep("save-order", "Order"), flowStep("save-audit", "AuditLog"));

        List<String> errors = validate(json);

        assertFalse(errors.stream().anyMatch(e -> e.contains("aggregate") && e.contains("GoodFlow")),
                "A concept no aggregate owns (AuditLog) is not a boundary to cross, got: " + errors);
    }
}
