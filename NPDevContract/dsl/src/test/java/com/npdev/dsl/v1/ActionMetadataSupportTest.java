package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledOrchestration;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionMetadataSupportTest {

    @Test
    void compilerCarriesActionMetadataAcrossFlowsTransitionsAndOrchestration() throws Exception {
        Path modelPath = Files.createTempFile("npdev-action-metadata-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "action.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "required": true, "enumValues": ["Scheduled", "CheckedIn", "Completed", "Cancelled"] },
                        { "name": "checkInTime", "type": "datetime" }
                      ],
                      "lifecycle": {
                        "statusField": "status",
                        "transitions": [
                          {
                            "from": "Scheduled",
                            "to": "CheckedIn",
                            "event": "AppointmentCheckedIn",
                            "actionLabel": "Check In",
                            "action": {
                              "label": "Check in patient",
                              "confirmationText": "Check in this patient?",
                              "successMessage": "Patient checked in.",
                              "failureHint": "Capture the check-in time before retrying.",
                              "dangerLevel": "low",
                              "visibleWhen": "status == 'Scheduled'",
                              "permissionHint": "appointments.checkin",
                              "inputFormHint": "appointment-checkin"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "events": [
                    {
                      "name": "AppointmentCheckedIn",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" },
                        { "name": "status", "type": "string" }
                      ]
                    }
                  ],
                  "orchestrationRules": [
                    {
                      "name": "NotifyCheckIn",
                      "trigger": { "type": "event", "event": "AppointmentCheckedIn" },
                      "actions": [
                        {
                          "type": "scheduleEvent",
                          "event": "AppointmentCheckedIn",
                          "delayMinutes": 5,
                          "action": {
                            "label": "Queue check-in follow-up",
                            "successMessage": "Follow-up queued.",
                            "dangerLevel": "low",
                            "permissionHint": "notifications.send",
                            "inputFormHint": "follow-up-send"
                          },
                          "map": {
                            "appointmentId": "$event.appointmentId"
                          }
                        }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "CreateAppointment",
                      "input": { "concept": "Appointment", "mode": "create" },
                      "action": {
                        "label": "Create appointment",
                        "confirmationText": "Create appointment?",
                        "successMessage": "Appointment created.",
                        "failureHint": "Review the appointment payload.",
                        "dangerLevel": "low",
                        "permissionHint": "appointments.create",
                        "inputFormHint": "appointment-create"
                      },
                      "steps": [
                        {
                          "name": "save-appointment",
                          "type": "createConcept",
                          "scope": "Appointment",
                          "input": "$input",
                          "out": "$saved",
                          "action": {
                            "label": "Persist appointment",
                            "successMessage": "Appointment persisted.",
                            "dangerLevel": "low",
                            "permissionHint": "appointments.create",
                            "inputFormHint": "appointment-create"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected valid action metadata specimen, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.findFlow("CreateAppointment").orElseThrow();
        assertNotNull(flow.getAction());
        assertEquals("Create appointment", flow.getAction().getLabel());
        assertEquals("appointments.create", flow.getAction().getPermissionHint());
        assertEquals("appointment-create", flow.getAction().getInputFormHint());

        assertEquals(1, flow.getSteps().size());
        assertNotNull(flow.getSteps().get(0).getAction());
        assertEquals("Persist appointment", flow.getSteps().get(0).getAction().getLabel());

        CompiledStateTransition transition = compiled.findConcept("Appointment")
                .orElseThrow()
                .getLifecycle()
                .getTransitions()
                .get(0);
        assertNotNull(transition.getAction());
        assertEquals("Check in patient", transition.getAction().getLabel());
        assertEquals("appointments.checkin", transition.getAction().getPermissionHint());

        CompiledOrchestration orchestration = compiled.getOrchestrationRules().get(0);
        assertEquals(1, orchestration.getActions().size());
        assertNotNull(orchestration.getActions().get(0).getAction());
        assertEquals("Queue check-in follow-up", orchestration.getActions().get(0).getAction().getLabel());
    }
}

