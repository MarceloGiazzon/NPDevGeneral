package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
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

class DslFlowModelTest {

    @Test
    void parsesValidatesAndCompilesFlows() throws Exception {
        String json = """
                {
                  "model": "IdentityDomain",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "email", "type": "string", "required": true },
                        { "name": "name", "type": "string" }
                      ],
                      "invariants": [
                        { "name": "emailUnique", "rule": "unique(email)" }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "PersistenceCapability",
                      "operations": [
                        { "name": "save", "input": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] }, "output": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] } }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "UserCreated", "payload": ["userId"] }
                  ],
                  "flows": [
                    {
                      "name": "CreateUser",
                      "concept": "User",
                      "inputSchema": {
                        "type": "object",
                        "required": ["email"],
                        "properties": {
                          "email": { "type": "string", "minLength": 5 },
                          "name": { "type": "string" }
                        }
                      },
                      "outputSchema": {
                        "type": "object",
                        "properties": {
                          "id": { "type": "string" },
                          "email": { "type": "string" }
                        }
                      },
                      "steps": [
                        { "name": "pre", "type": "invariant", "checkpoint": "pre", "invariants": ["emailUnique"] },
                        { "name": "save", "type": "capability", "capability": "PersistenceCapability", "operation": "save", "input": "$input", "output": "$saved" },
                        { "name": "emit", "type": "event", "event": "UserCreated", "payload": "$saved" }
                      ]
                    }
                  ]
                }
                """;

        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getFlows().size());

        CompiledFlow flow = compiled.findFlow("CreateUser").orElseThrow();
        assertEquals("User", flow.getConcept());
        assertEquals(3, flow.getSteps().size());
        assertEquals("object", flow.getInputSchema().getType());
        assertEquals(List.of("email"), flow.getInputSchema().getRequired());
        assertEquals("string", flow.getInputSchema().getProperties().get("email").getType());
        assertEquals(5, flow.getInputSchema().getProperties().get("email").getMinLength());
        assertEquals("object", flow.getOutputSchema().getType());
        assertEquals("string", flow.getOutputSchema().getProperties().get("id").getType());

        CompiledFlowStep capabilityStep = flow.getSteps().get(1);
        assertEquals("capability", capabilityStep.getType());
        assertEquals("PersistenceCapability", capabilityStep.getCapabilityCall().getCapability());
        assertEquals("save", capabilityStep.getCapabilityCall().getOperation());
        assertEquals("$input", capabilityStep.getCapabilityCall().getArgsRefs().get(0));
        assertEquals("$saved", capabilityStep.getCapabilityCall().getOutputRef());
    }

    @Test
    void validatorRejectsUnknownFlowReferencesAndTechKeywords() throws Exception {
        String json = """
                {
                  "namespace": "x",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "SpringInvoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "email", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "name": "emailUnique", "rule": "unique(email)" }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "PersistenceCapability",
                      "operations": [
                        { "name": "save", "input": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] }, "output": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] } }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "InvoiceCreated", "payload": ["id"] }
                  ],
                  "flows": [
                    {
                      "name": "CreateInvoice",
                      "concept": "SpringInvoice",
                      "steps": [
                        { "name": "pre", "type": "invariant", "checkpoint": "pre", "invariants": ["missingInvariant"] },
                        { "name": "cap", "type": "capability", "capability": "PersistenceCapability", "operation": "missingOp" },
                        { "name": "evt", "type": "event", "event": "MissingEvent", "payload": "$input" }
                      ]
                    }
                  ]
                }
                """;

        List<String> errors = new SemanticValidator().validate(parse(json));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown invariant")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown operation")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown event")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("technology-neutral")));
    }

    @Test
    void validatorRejectsDuplicateInvariantNamesPerConcept() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "email", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "name": "EmailRule", "expr": "email != null" },
                        { "name": "EmailRule", "rule": "unique(email)" }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "CreateUser",
                      "concept": "User",
                      "steps": [
                        { "name": "pre", "type": "invariant", "scope": "User" }
                      ]
                    }
                  ]
                }
                """;

        List<String> errors = new SemanticValidator().validate(parse(json));
        assertTrue(errors.stream().anyMatch(e ->
                e.contains("duplicate invariant name")
                        || e.contains("CONFLICT_DUPLICATE_MEMBER")));
    }

    @Test
    void validatorRejectsDuplicateConceptEventAndFlowNames() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "UserCreated" },
                    { "name": "UserCreated" }
                  ],
                  "flows": [
                    {
                      "name": "CreateUser",
                      "concept": "User",
                      "steps": [{ "type": "return", "value": "$input" }]
                    },
                    {
                      "name": "CreateUser",
                      "concept": "User",
                      "steps": [{ "type": "return", "value": "$input" }]
                    }
                  ]
                }
                """;

        List<String> errors = new SemanticValidator().validate(parse(json));
        assertTrue(errors.stream().anyMatch(e ->
                e.contains("Duplicate concept name")
                        || e.contains("Duplicate event name")
                        || e.contains("Duplicate flow name")
                        || e.contains("CONFLICT_DUPLICATE_MEMBER")));
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-flows-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}



