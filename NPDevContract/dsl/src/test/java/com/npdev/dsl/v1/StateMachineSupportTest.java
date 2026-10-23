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
        CompiledLifecycle lifecycle = compiled.findConcept("Appointment").orElseThrow().getLifecycle();
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

    /** REG-278: terminal means final -- a transition out of a terminal state is a model error, one into it is not. */
    @Test
    void semanticValidationRejectsATransitionOutOfATerminalState() throws Exception {
        Path modelPath = Files.createTempFile("npdev-state-machine-terminal-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "state.machine.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Review",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "status",
                          "type": "enum",
                          "required": true,
                          "enumValues": ["Draft", "Rejected"]
                        }
                      ],
                      "lifecycle": {
                        "statusField": "status",
                        "states": [
                          { "value": "Draft", "initial": true },
                          { "value": "Rejected", "terminal": true }
                        ],
                        "transitions": [
                          { "from": "Draft", "to": "Rejected" },
                          { "from": "Rejected", "to": "Draft" }
                        ]
                      }
                    }
                  ]
                }
                """);

        List<String> errors = new SemanticValidator().validate(new JsonModelParser().parse(modelPath));
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("leaves terminal state 'Rejected'")),
                "Expected the terminal-state exit to be rejected, got: " + errors
        );
        assertEquals(1, errors.stream().filter(error -> error.contains("terminal state")).count(),
                "Only the exit from Rejected is wrong; Draft -> Rejected (into a terminal state) is fine: " + errors);
    }

    /** Pigmentampas 2026-10-07: a guard naming $user (role-gated transition) validates; a malformed one does not. */
    @Test
    void semanticValidationAcceptsUserGuardsAndRejectsMalformedOnes() throws Exception {
        assertTrue(guardErrors("$user.roles.contains('Curator') || $user.id == 'admin'").isEmpty());
        assertTrue(guardErrors("$user.roles.contains('Curator'").stream()
                .anyMatch(error -> error.contains("guard uses an unsupported expression format")));
    }

    /** REG-285: an unbound $user name in a guard evaluates as null at runtime -- refused at validation. */
    @Test
    void semanticValidationRejectsAGuardNamingAnUnboundUserValue() throws Exception {
        assertTrue(guardErrors("!$user.isSytem").stream()
                .anyMatch(error -> error.contains("guard references '$user.isSytem'")));
        assertTrue(guardErrors("$user.isSystem || $user.roles.contains('Curator')").isEmpty());
    }

    private static List<String> guardErrors(String guard) throws Exception {
        Path modelPath = Files.createTempFile("npdev-state-machine-guard-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "state.machine.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Cap",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "required": true, "enumValues": ["SUBMITTED", "APPROVED"] }
                      ],
                      "lifecycle": {
                        "statusField": "status",
                        "states": [ { "value": "SUBMITTED", "initial": true }, { "value": "APPROVED" } ],
                        "transitions": [ { "from": "SUBMITTED", "to": "APPROVED", "guard": %s } ]
                      }
                    }
                  ]
                }
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(guard)));
        return new SemanticValidator().validate(new JsonModelParser().parse(modelPath));
    }
}
