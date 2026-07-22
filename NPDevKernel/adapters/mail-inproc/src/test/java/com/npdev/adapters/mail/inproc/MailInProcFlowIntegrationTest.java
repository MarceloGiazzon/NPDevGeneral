package com.npdev.adapters.mail.inproc;

import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.FlowDefinition;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowDefinitionProvider;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-11 DoD: "A flow step sends a templated email through SMTP in the compose stack; the
 * inproc adapter lets the runtimehost gate assert on sent mail without a network." This proves
 * the second half -- a flow's capabilityCall step dispatches through the real production
 * CapabilityRegistry/RegistryCapabilityDispatcher/KernelRunner wiring to a real
 * InProcMailCapabilityAdapter, and the rendered (not raw) template is what gets recorded.
 */
final class MailInProcFlowIntegrationTest {

    @Test
    void flowStepSendsTemplatedEmailAndInProcAdapterRecordsIt() {
        InProcMailCapabilityAdapter mailAdapter = new InProcMailCapabilityAdapter();
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("mail", "EmailCapability", "mail-inproc", mailAdapter);
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        FlowDefinition sendWelcomeEmail = new FlowDefinition(
                "SendWelcomeEmail",
                "Notification",
                List.of(
                        FlowStepDefinition.capabilityCall(
                                "send-welcome-email",
                                "mail",
                                "EmailCapability",
                                "mail-inproc",
                                "send",
                                List.of("$input"),
                                "$delivery",
                                CapabilityExecutionPolicy.defaults()
                        ),
                        FlowStepDefinition.returnValue("return", "$delivery")
                ),
                null,
                null
        );
        FlowDefinitionProvider flowProvider = flowName ->
                "SendWelcomeEmail".equals(flowName) ? Optional.of(sendWelcomeEmail) : Optional.empty();

        KernelRunner runner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                flowProvider,
                dispatcher,
                ExecutionTracer.NOOP,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                JsonCodec.noop(),
                (schema, payload) -> List.of()
        );

        ExecutionResult result = runner.executeFlow("SendWelcomeEmail", Map.of(
                "to", "ada@example.com",
                "subject", "Welcome, ${name}",
                "body", "Hi ${name}, thanks for joining.",
                "templateVars", Map.of("name", "Ada")
        ));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(1, mailAdapter.deliveries().size());
        Map<String, Object> delivery = mailAdapter.deliveries().get(0);
        assertEquals("Welcome, Ada", delivery.get("subject"));
        assertEquals("Hi Ada, thanks for joining.", delivery.get("body"));
        assertEquals(List.of("ada@example.com"), delivery.get("to"));
        assertEquals("sent", delivery.get("status"));
        assertTrue(delivery.get("deliveryId") != null);
    }
}
