package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultProcedureExecutorTest {

    /** O5 (Move 11 W4): the REAL governed semantic policy every generated app runs, not the noop
     * default -- see GovernedTestGateways. A write-path test on a noop policy is a test that starts
     * downstream of the layer REG-71/REG-83 both broke. */
    private static GovernedTestGateways.ConceptSpec[] concepts() {
        return new ConceptSpec[]{ ConceptSpec.of("ContactMessage", "message", "subject", "email", "name") };
    }

    private static com.npdev.kernel.concepts.ConceptGateway governedGateway() {
        return GovernedTestGateways.forConcepts(concepts());
    }

    @Test
    void executesConceptCapabilityAndEventStepsInOrder() {
        CapturingEventBus eventBus = new CapturingEventBus();
        CapabilityDispatcher dispatcher = (call, state) -> CapabilityResult.success(Map.of("sent", true, "args", call.args().size()));
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                governedGateway(),
                dispatcher,
                eventBus
        );
        ProcedureDefinition definition = new ProcedureDefinition(
                "RegisterContact",
                List.of(
                        ProcedureStep.saveConcept("save-contact", "ContactMessage", "id", "payload", "saved"),
                        ProcedureStep.readConcept("read-contact", "ContactMessage", "id", "loaded"),
                        ProcedureStep.callCapability("notify", "notification", "NotificationCapability", "inproc", "send",
                                List.of("loaded"), "notificationResult"),
                        ProcedureStep.publishEvent("publish", "ContactRegistered", "loaded")
                )
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of("id", "contact-1", "payload", Map.of("email", "a@example.test")),
                ExecutionContext.of("tenant-a", "actor-a").withTag("correlationId", "corr-1")
        );

        assertTrue(result.ok());
        assertEquals(4, result.steps().size());
        assertTrue(result.steps().stream().allMatch(ProcedureStepResult::ok));
        assertTrue(result.state().containsKey("saved"));
        assertEquals(Map.of("sent", true, "args", 1), result.state().get("notificationResult"));
        assertEquals(1, eventBus.events.size());
        assertEquals("ContactRegistered", eventBus.events.get(0).eventName());
        assertEquals("tenant-a", eventBus.events.get(0).tenantId());
    }

    @Test
    void stopsAfterCapabilityFailure() {
        CapturingEventBus eventBus = new CapturingEventBus();
        CapabilityDispatcher dispatcher = (call, state) -> CapabilityResult.failure(
                "NOTIFICATION_DOWN",
                "notification unavailable",
                CapabilityErrorKind.TRANSIENT,
                Map.of()
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                governedGateway(),
                dispatcher,
                eventBus
        );
        ProcedureDefinition definition = new ProcedureDefinition(
                "RegisterContact",
                List.of(
                        ProcedureStep.saveConcept("save-contact", "ContactMessage", "id", "payload", "saved"),
                        ProcedureStep.callCapability("notify", "notification", "NotificationCapability", "inproc", "send",
                                List.of("saved"), "notificationResult"),
                        ProcedureStep.publishEvent("publish", "ContactRegistered", "saved")
                )
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of("id", "contact-1", "payload", Map.of("email", "a@example.test")),
                ExecutionContext.of("tenant-a", "actor-a")
        );

        assertFalse(result.ok());
        assertEquals("NOTIFICATION_DOWN", result.failureCode());
        assertEquals(2, result.steps().size());
        assertTrue(eventBus.events.isEmpty());
    }

    @Test
    void executesBranchLoopListAndReturnStepsThroughConceptGateway() {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                governedGateway(),
                (call, state) -> CapabilityResult.success(Map.of()),
                new CapturingEventBus()
        );
        ProcedureDefinition definition = new ProcedureDefinition(
                "RegisterExpenses",
                List.of(
                        ProcedureStep.ifThenElse(
                                "when-enabled",
                                "enabled",
                                List.of(
                                        ProcedureStep.forEach(
                                                "save-each-expense",
                                                "items",
                                                "item",
                                                List.of(ProcedureStep.saveConcept(
                                                        "save-expense",
                                                        "Expense",
                                                        "item.id",
                                                        "item.payload",
                                                        "lastSaved"
                                                ))
                                        ),
                                        ProcedureStep.listConcepts("list-expenses", "Expense", "records"),
                                        ProcedureStep.returnValue("return-records", "$records")
                                ),
                                List.of(ProcedureStep.returnValue("return-empty", "$input"))
                        )
                )
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of(
                        "enabled", true,
                        "items", List.of(
                                Map.of("id", "expense-1", "payload", Map.of("amount", 20)),
                                Map.of("id", "expense-2", "payload", Map.of("amount", 30))
                        )
                ),
                ExecutionContext.of("tenant-a", "actor-a").withTag("executionMode", "headless")
        );

        assertTrue(result.ok());
        @SuppressWarnings("unchecked")
        List<Object> returned = (List<Object>) result.state().get("return");
        assertEquals(2, returned.size());
    }

    @Test
    void callProcedureReturnsNestedProcedureOutput() {
        ProcedureDefinition child = new ProcedureDefinition(
                "BuildPayload",
                List.of(
                        ProcedureStep.mapValue("copy-input", "$input", "copied"),
                        ProcedureStep.returnValue("return-copy", "$copied")
                )
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                governedGateway(),
                (call, state) -> CapabilityResult.success(Map.of()),
                new CapturingEventBus(),
                Map.of("BuildPayload", child)
        );
        ProcedureDefinition parent = new ProcedureDefinition(
                "Parent",
                List.of(
                        ProcedureStep.callProcedure("call-child", "BuildPayload", "input", "childOutput"),
                        ProcedureStep.returnValue("return-child", "$childOutput")
                )
        );

        ProcedureExecutionResult result = executor.execute(
                parent,
                Map.of("value", "hello"),
                ExecutionContext.of("tenant-a", "actor-a")
        );

        assertTrue(result.ok());
        assertEquals(Map.of("value", "hello"), result.state().get("return"));
    }

    @Test
    void failsDeterministicallyWhenProcedureRecursionLimitIsExceeded() {
        ProcedureDefinition recursive = new ProcedureDefinition(
                "Recursive",
                List.of(ProcedureStep.callProcedure("again", "Recursive", "input", "out"))
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                governedGateway(),
                (call, state) -> CapabilityResult.success(Map.of()),
                new CapturingEventBus(),
                Map.of("Recursive", recursive),
                new ProcedureExecutionLimits(100, 2, 100)
        );

        ProcedureExecutionResult result = executor.execute(
                recursive,
                Map.of("value", "hello"),
                ExecutionContext.of("tenant-a", "actor-a")
        );

        assertFalse(result.ok());
        assertEquals("PROCEDURE_RECURSION_LIMIT_EXCEEDED", result.failureCode());
    }

    @Test
    void failsDeterministicallyWhenProcedureLoopLimitIsExceeded() {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                governedGateway(),
                (call, state) -> CapabilityResult.success(Map.of()),
                new CapturingEventBus(),
                Map.of(),
                new ProcedureExecutionLimits(100, 10, 1)
        );
        ProcedureDefinition definition = new ProcedureDefinition(
                "Loop",
                List.of(ProcedureStep.forEach(
                        "limited-loop",
                        "items",
                        "item",
                        List.of(ProcedureStep.mapValue("copy", "$item", "last"))
                ))
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of("items", List.of("one", "two")),
                ExecutionContext.of("tenant-a", "actor-a")
        );

        assertFalse(result.ok());
        assertEquals("PROCEDURE_LOOP_LIMIT_EXCEEDED", result.failureCode());
    }

    private static final class CapturingEventBus implements EventBus {
        private final List<EventEnvelope> events = new ArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            events.add(event);
        }
    }
}
