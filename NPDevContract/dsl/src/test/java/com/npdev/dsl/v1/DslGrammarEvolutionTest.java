package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslGrammarEvolutionTest {

    @Test
    void parsesAndCompilesCapabilitiesEventsAndFlows() throws Exception {
        String json = """
                {
                  "model": "SalesDomain",
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
                        { "rule": "unique(email)" },
                        { "rule": "email.matches(\\".+@.+\\")" }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "EmailSender",
                      "operations": [
                        { "name": "send", "input": { "type": "object", "properties": { "email": { "type": "string" }, "template": { "type": "string" } }, "required": ["email", "template"] }, "output": { "type": "object", "properties": { "status": { "type": "string" } }, "required": ["status"] } }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "eventBus", "adapter": "inproc" },
                    { "capability": "persistence", "adapter": "repository" }
                  ],
                  "events": [
                    { "name": "UserCreated", "payload": ["userId"] }
                  ],
                  "flows": [
                    {
                      "name": "UserOnboarding",
                      "input": { "concept": "User", "mode": "create" },
                      "steps": [
                        { "type": "emitEvent", "event": "UserCreated", "from": "User" },
                        { "type": "return", "value": "User" }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelFile);

        assertEquals("SalesDomain", ast.getNamespace());
        assertEquals(1, ast.getCapabilities().size());
        assertEquals(2, ast.getBindings().size());
        assertEquals(1, ast.getEvents().size());
        assertEquals(1, ast.getFlows().size());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getCapabilities().size());
        assertEquals(2, compiled.getBindings().size());
        assertEquals(1, compiled.getEvents().size());
        assertEquals(1, compiled.getFlows().size());
        assertEquals(1, compiled.findEntity("User").orElseThrow().getExpressionInvariants().size());
    }

    @Test
    void parsesNestedObjectAndArrayObjectFields() throws Exception {
        String json = """
                {
                  "namespace": "clinic",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "emergencyContact",
                          "type": "object",
                          "properties": {
                            "name": { "type": "string" },
                            "phone": { "type": "string" },
                            "authorizedForDisclosure": { "type": "boolean", "default": false }
                          },
                          "required": ["name", "phone"]
                        },
                        {
                          "name": "allergies",
                          "type": "array",
                          "items": {
                            "type": "object",
                            "properties": {
                              "allergen": { "type": "string" },
                              "severity": { "type": "enum", "enumValues": ["Mild", "Severe"] }
                            },
                            "required": ["allergen"]
                          }
                        }
                      ],
                      "invariants": [
                        { "name": "NoDuplicateAllergies", "type": "expression", "expression": "allergies.uniqueBy(allergen)" }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-nested-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected nested model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        var patient = compiled.findEntity("Patient").orElseThrow();
        var emergencyContact = patient.getFields().stream()
                .filter(field -> "emergencyContact".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        var allergies = patient.getFields().stream()
                .filter(field -> "allergies".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("object", emergencyContact.getDslType());
        assertEquals("com.fasterxml.jackson.databind.JsonNode", emergencyContact.getJavaType());
        assertEquals("object", emergencyContact.getSchema().getType());
        assertEquals("boolean", emergencyContact.getSchema().getProperties().get("authorizedForDisclosure").getType());

        assertEquals("array", allergies.getDslType());
        assertEquals("array", allergies.getSchema().getType());
        assertEquals("object", allergies.getSchema().getItems().getType());
        assertEquals(List.of("allergen"), allergies.getSchema().getItems().getRequired());
    }


    @Test
    void compilerMapsDatetimeToOffsetDateTime() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "checkInTime", "type": "datetime" }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-datetime-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected datetime model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        var appointment = compiled.findEntity("Appointment").orElseThrow();
        var checkInTime = appointment.getFields().stream()
                .filter(field -> "checkInTime".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("datetime", checkInTime.getDslType());
        assertEquals("java.time.OffsetDateTime", checkInTime.getJavaType());
    }

    @Test
    void parsesAndCompilesOrchestrationRulesV1() throws Exception {
        String json = """
                {
                  "namespace": "clinic",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "InsuranceClaim",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "appointmentId", "type": "reference", "ref": "Appointment", "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Draft", "Submitted"] }
                      ]
                    }
                  ],
                  "events": [
                    {
                      "name": "AppointmentCompleted",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" }
                      ]
                    }
                  ],
                  "orchestrationRules": [
                    {
                      "name": "CreateDraftClaim",
                      "trigger": { "type": "event", "event": "AppointmentCompleted" },
                      "action": {
                        "type": "create",
                        "concept": "InsuranceClaim",
                        "map": {
                          "appointmentId": "$event.appointmentId",
                          "status": "Draft"
                        }
                      }
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-orchestration-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        assertEquals(1, ast.getOrchestrationRules().size());

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getOrchestrationRules().size());
        var orchestration = compiled.getOrchestrationRules().get(0);
        assertEquals("AppointmentCompleted", orchestration.getTrigger().getEvent());
        assertEquals("InsuranceClaim", orchestration.getAction().getConcept());
        assertEquals("Draft", orchestration.getAction().getMap().get("status"));
        assertFalse(orchestration.getAction().getMap().isEmpty());
    }

    @Test
    void parsesAndCompilesCallCapabilityOrchestrationRule() throws Exception {
        String json = """
                {
                  "namespace": "clinic",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "AuditNotifier",
                      "type": "NotificationCapability",
                      "operations": [
                        { "name": "send", "input": { "type": "object", "properties": { "appointmentId": { "type": "string" }, "status": { "type": "string" } }, "required": ["appointmentId", "status"] } }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "AuditNotifier", "adapter": "notification-inproc" }
                  ],
                  "events": [
                    {
                      "name": "AppointmentCompleted",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" },
                        { "name": "status", "type": "string" }
                      ]
                    }
                  ],
                  "orchestrationRules": [
                    {
                      "name": "NotifyOnAppointmentCompleted",
                      "trigger": { "type": "event", "event": "AppointmentCompleted" },
                      "condition": "$event.status == \\"Completed\\"",
                      "action": {
                        "type": "callCapability",
                        "capability": "AuditNotifier",
                        "operation": "send",
                        "map": {
                          "appointmentId": "$event.appointmentId",
                          "status": "$event.status"
                        }
                      }
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-orchestration-capability-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getOrchestrationRules().size());
        var orchestration = compiled.getOrchestrationRules().get(0);
        assertEquals("callCapability", orchestration.getAction().getType());
        assertEquals("AuditNotifier", orchestration.getAction().getCapability());
        assertEquals("send", orchestration.getAction().getOperation());
        assertEquals("$event.status == \"Completed\"", orchestration.getCondition());
    }

    @Test
    void parsesAndCompilesMultiActionOrchestrationRule() throws Exception {
        String json = """
                {
                  "namespace": "clinic",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "InsuranceClaim",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "appointmentId", "type": "reference", "ref": "Appointment", "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Draft", "Submitted"] }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "notification",
                      "type": "NotificationCapability",
                      "operations": [
                        { "name": "send", "input": { "type": "object", "properties": { "appointmentId": { "type": "string" }, "status": { "type": "string" } }, "required": ["appointmentId", "status"] } }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "notification", "adapter": "notification-inproc" }
                  ],
                  "events": [
                    {
                      "name": "AppointmentCompleted",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" },
                        { "name": "status", "type": "string" }
                      ]
                    }
                  ],
                  "orchestrationRules": [
                    {
                      "name": "CompleteAppointmentFlow",
                      "trigger": { "type": "event", "event": "AppointmentCompleted" },
                      "condition": "$event.status == \\"Completed\\"",
                      "actions": [
                        {
                          "type": "create",
                          "concept": "InsuranceClaim",
                          "map": {
                            "appointmentId": "$event.appointmentId",
                            "status": "Draft"
                          }
                        },
                        {
                          "type": "callCapability",
                          "capability": "notification",
                          "operation": "send",
                          "map": {
                            "appointmentId": "$event.appointmentId",
                            "status": "$event.status"
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-orchestration-multiaction-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getOrchestrationRules().size());
        var orchestration = compiled.getOrchestrationRules().get(0);
        assertEquals("AppointmentCompleted", orchestration.getTrigger().getEvent());
        assertEquals(2, orchestration.getActions().size());
        assertEquals("create", orchestration.getActions().get(0).getType());
        assertEquals("InsuranceClaim", orchestration.getActions().get(0).getConcept());
        assertEquals("callCapability", orchestration.getActions().get(1).getType());
        assertEquals("notification", orchestration.getActions().get(1).getCapability());
    }

    @Test
    void orchestrationConditionMustReferenceKnownEventField() throws Exception {
        String json = """
                {
                  "namespace": "clinic",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "InsuranceClaim",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "appointmentId", "type": "reference", "ref": "Appointment", "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Draft", "Submitted"] }
                      ]
                    }
                  ],
                  "events": [
                    {
                      "name": "AppointmentCompleted",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" },
                        { "name": "status", "type": "string" }
                      ]
                    }
                  ],
                  "orchestrationRules": [
                    {
                      "name": "CreateDraftClaim",
                      "trigger": { "type": "event", "event": "AppointmentCompleted" },
                      "condition": "$event.nonExisting == \\"Completed\\"",
                      "action": {
                        "type": "create",
                        "concept": "InsuranceClaim",
                        "map": {
                          "appointmentId": "$event.appointmentId",
                          "status": "Draft"
                        }
                      }
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-orchestration-condition-invalid-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertFalse(validation.getErrors().isEmpty(), "Expected semantic error for invalid condition field");
        assertTrue(validation.getErrors().stream().anyMatch(error ->
                        error.contains("condition references unknown event payload field")),
                "Expected condition field validation error, got: " + validation.getErrors());
    }

    @Test
    void parsesAndCompilesScheduleEventOrchestrationAction() throws Exception {
        String json = """
                {
                  "namespace": "clinic",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "events": [
                    {
                      "name": "AppointmentCompleted",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" },
                        { "name": "status", "type": "string" }
                      ]
                    },
                    {
                      "name": "InsuranceClaimFollowUpDue",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" },
                        { "name": "status", "type": "string" }
                      ]
                    }
                  ],
                  "orchestrationRules": [
                    {
                      "name": "ScheduleFollowUp",
                      "trigger": { "type": "event", "event": "AppointmentCompleted" },
                      "actions": [
                        {
                          "type": "scheduleEvent",
                          "event": "InsuranceClaimFollowUpDue",
                          "delaySeconds": 60,
                          "map": {
                            "appointmentId": "$event.appointmentId",
                            "status": "FollowUpDue"
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-orchestration-schedule-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getOrchestrationRules().size());
        var action = compiled.getOrchestrationRules().get(0).getActions().get(0);
        assertEquals("scheduleEvent", action.getType());
        assertEquals("InsuranceClaimFollowUpDue", action.getEvent());
        assertEquals(60L, action.getDelaySeconds());
    }

    @Test
    void semanticValidatorFlagsDuplicateCapabilityAndInvalidBinding() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "email", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "type": "unique", "fields": ["email"] }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name": "Persistence" },
                    { "name": "Persistence" }
                  ],
                  "bindings": [
                    { "capability": "unknown", "adapter": "x" },
                    { "capability": "unknown", "adapter": "y" }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-invalid-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelFile);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(errors.stream().anyMatch(e ->
                e.contains("Duplicate capability name: Persistence")
                        || e.contains("CONFLICT_DUPLICATE_MEMBER")));
        assertTrue(errors.stream().anyMatch(e ->
                e.contains("Binding references unknown capability: unknown")
                        || e.contains("Duplicate binding for capability: unknown")
                        || e.contains("CONFLICT_DUPLICATE_MEMBER")));
    }

    @Test
    void parserRejectsModelMissingDslVersionEvenIfVersionExists() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-missing-dsl-version-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> new JsonModelParser().parse(modelFile));
        assertTrue(error.getMessage().contains("Missing required field: dslVersion"));
    }

    @Test
    void parserRejectsUnsupportedDslVersion() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "2.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-bad-dsl-version-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> new JsonModelParser().parse(modelFile));
        assertTrue(error.getMessage().contains("Unsupported dslVersion"));
    }

    @Test
    void parserRejectsUnknownRootKeyViaSchemaValidation() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "unexpectedRootKey": "boom",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """;

        Path modelFile = Files.createTempFile("npdev-grammar-unknown-root-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> new JsonModelParser().parse(modelFile));
        assertTrue(error.getMessage().contains("Model schema validation failed"));
        assertTrue(error.getMessage().contains("unexpectedRootKey"));
    }
}


