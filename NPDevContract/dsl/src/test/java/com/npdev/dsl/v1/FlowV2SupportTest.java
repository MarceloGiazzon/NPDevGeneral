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

class FlowV2SupportTest {

    @Test
    void parsesValidatesAndCompilesFlowV2AliasesAndDelayMetadata() throws Exception {
        String json = """
                {
                  "namespace": "clinic.flowv2",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "name": "StatusRequired", "expr": "status != null && status != ''" }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name": "persistence", "type": "PersistenceCapability", "operations": ["save"] },
                    { "name": "notification", "type": "NotificationCapability", "operations": ["send"] }
                  ],
                  "bindings": [
                    { "capability": "persistence", "adapter": "repository" },
                    { "capability": "notification", "adapter": "notification-inproc" },
                    { "capability": "eventBus", "adapter": "inproc" }
                  ],
                  "events": [
                    { "name": "AppointmentReminderDue", "payload": [{ "name": "appointmentId", "type": "uuid" }] },
                    { "name": "AppointmentConfirmed", "payload": [{ "name": "appointmentId", "type": "uuid" }] }
                  ],
                  "flows": [
                    {
                      "name": "CreateAppointment",
                      "input": { "concept": "Appointment", "mode": "create" },
                      "steps": [
                        { "name": "validate-appointment", "type": "validate", "scope": "Appointment", "invariants": ["StatusRequired"] },
                        { "name": "save-appointment", "type": "createConcept", "scope": "Appointment", "input": "$input", "out": "$saved" },
                        { "name": "capture-id", "type": "assign", "input": "$saved.id", "out": "$appointmentId" },
                        { "name": "notify", "type": "callCapability", "cap": "notification", "op": "send", "args": ["$saved"], "out": "$notification" },
                        { "name": "queue-reminder", "type": "scheduleEvent", "event": "AppointmentReminderDue", "delayMinutes": 5, "data": { "appointmentId": "$saved.id" } },
                        { "name": "wait-confirmation", "type": "waitForEvent", "awaitEvent": "AppointmentConfirmed", "as": "confirmation", "match": { "correlation": true, "payload": { "appointmentId": "$appointmentId" } } },
                        { "name": "return-confirmation", "type": "return", "value": "$confirmation" }
                      ]
                    }
                  ]
                }
                """;

        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.findFlow("CreateAppointment").orElseThrow();

        assertEquals(7, flow.getSteps().size());
        assertEquals("invariant", flow.getSteps().get(0).getType());
        assertEquals("createConcept", flow.getSteps().get(1).getType());
        assertEquals("map", flow.getSteps().get(2).getType());
        assertEquals("capability", flow.getSteps().get(3).getType());

        CompiledFlowStep scheduled = flow.getSteps().get(4);
        assertEquals("scheduleEvent", scheduled.getType());
        assertEquals("AppointmentReminderDue", scheduled.getEventName());
        assertEquals(300L, scheduled.getDelaySeconds());
        assertEquals("$saved.id", scheduled.getEventDataRefs().get("appointmentId"));

        CompiledFlowStep awaited = flow.getSteps().get(5);
        assertEquals("await", awaited.getType());
        assertEquals("AppointmentConfirmed", awaited.getAwaitEventName());
        assertEquals(Boolean.TRUE, awaited.getAwaitMatchCorrelation());
        assertEquals("$appointmentId", awaited.getAwaitPayloadMatch().get("appointmentId"));
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-flow-v2-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}

