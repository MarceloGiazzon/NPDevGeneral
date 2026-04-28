package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineSupportTest {

    @Test
    void parserValidatorAndCompilerSupportExplicitStateMachineMetadata() throws Exception {
        Path modelPath = Files.createTempFile("npdev-state-machine-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "state.machine.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "status",
                          "type": "enum",
                          "required": true,
                          "enumValues": ["Scheduled", "CheckedIn", "Completed", "Cancelled"]
                        },
                        { "name": "checkInTime", "type": "string" },
                        { "name": "checkOutTime", "type": "string" }
                      ],
                      "lifecycle": {
                        "statusField": "status",
                        "states": [
                          { "value": "Scheduled", "label": "Scheduled", "initial": true, "metadata": { "lane": "active" } },
                          { "value": "CheckedIn", "label": "Checked In", "metadata": { "lane": "active" } },
                          { "value": "Completed", "label": "Completed", "terminal": true, "metadata": { "lane": "terminal" } },
                          { "value": "Cancelled", "label": "Cancelled", "terminal": true, "metadata": { "lane": "terminal" } }
                        ],
                        "transitions": [
                          {
                            "from": "Scheduled",
                            "to": "CheckedIn",
                            "requiredPayload": ["checkInTime"],
                            "guard": "checkInTime != null",
                            "event": "AppointmentCheckedIn",
                            "actionLabel": "Check In",
                            "metadata": { "intent": "check-in" }
                          },
                          {
                            "from": "CheckedIn",
                            "to": "Completed",
                            "requiredPayload": ["checkOutTime"],
                            "guard": "checkOutTime != null",
                            "event": "AppointmentCompleted",
                            "actionLabel": "Complete Appointment",
                            "metadata": { "intent": "complete" }
                          }
                        ]
                      }
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected explicit state machine metadata to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledLifecycle lifecycle = compiled.findEntity("Appointment").orElseThrow().getLifecycle();
        assertEquals(4, lifecycle.getStates().size());
        CompiledStateMachineState scheduled = lifecycle.getStates().get(0);
        assertEquals("Scheduled", scheduled.getValue());
        assertTrue(scheduled.isInitial());
        assertEquals("active", scheduled.getMetadata().get("lane"));

        CompiledStateTransition checkedIn = lifecycle.getTransitions().stream()
                .filter(transition -> "Scheduled".equals(transition.getFrom()) && "CheckedIn".equals(transition.getTo()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("checkInTime"), checkedIn.getRequiredPayload());
        assertEquals("checkInTime != null", checkedIn.getGuard());
        assertEquals("Check In", checkedIn.getActionLabel());
        assertEquals("check-in", checkedIn.getMetadata().get("intent"));
    }

    @Test
    void semanticValidationRejectsMultipleInitialStates() throws Exception {
        Path modelPath = Files.createTempFile("npdev-state-machine-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "state.machine.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "status",
                          "type": "enum",
                          "required": true,
                          "enumValues": ["Scheduled", "CheckedIn"]
                        }
                      ],
                      "lifecycle": {
                        "statusField": "status",
                        "states": [
                          { "value": "Scheduled", "initial": true },
                          { "value": "CheckedIn", "initial": true }
                        ],
                        "transitions": [
                          { "from": "Scheduled", "to": "CheckedIn", "actionLabel": "Check In" }
                        ]
                      }
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertFalse(errors.isEmpty(), "Expected semantic validation to reject multiple initial states.");
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("exactly one initial state")),
                "Expected initial-state validation error, got: " + errors
        );
    }
}

