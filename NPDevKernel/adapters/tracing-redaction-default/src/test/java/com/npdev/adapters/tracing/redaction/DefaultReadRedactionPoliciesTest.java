package com.npdev.adapters.tracing.redaction;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultReadRedactionPoliciesTest {

    @Test
    void executionRedactionShowsOnlyKeysForUserAndAllowlistedValuesForDebug() {
        DefaultExecutionRedactionPolicy policy = new DefaultExecutionRedactionPolicy();
        FlowInstance instance = new FlowInstance(
                "exec-1",
                "CreateUser",
                "corr-1",
                "tenant-1",
                "actor-1",
                2,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("status", "PENDING", "entityId", "u-1", "token", "abc", "email", "ana@acme.com"),
                "UserApproved",
                1000L,
                2000L
        );

        FlowInstance redactedUser = policy.redact(instance, ExecutionContext.of("tenant-1", "actor-1"));
        assertEquals("exec-1", redactedUser.executionId());
        assertEquals("***", redactedUser.state().get("status"));
        assertEquals("***", redactedUser.state().get("entityId"));
        assertEquals("***", redactedUser.state().get("token"));

        FlowInstance redactedDebug = policy.redact(
                instance,
                ExecutionContext.of("tenant-1", "actor-1").withRoles(Set.of("DEBUG"))
        );
        assertEquals("PENDING", redactedDebug.state().get("status"));
        assertEquals("u-1", redactedDebug.state().get("entityId"));
        assertEquals("***", redactedDebug.state().get("token"));
        assertEquals("***", redactedDebug.state().get("email"));
    }

    @Test
    void eventRedactionUsesDebugAllowlistAndMasksSecrets() {
        DefaultEventRedactionPolicy policy = new DefaultEventRedactionPolicy();
        EventEnvelope event = EventEnvelope.of(
                "UserCreated",
                Map.of("status", "OK", "entityId", "u-1", "secret", "x", "token", "abc"),
                "corr-1",
                "cause-1",
                "CreateUser",
                1,
                Map.of(),
                "tenant-1",
                "actor-1"
        );

        EventEnvelope redactedUser = policy.redact(event, ExecutionContext.of("tenant-1", "actor-1"));
        assertEquals("UserCreated", redactedUser.eventName());
        assertTrue(redactedUser.payload().isEmpty());

        EventEnvelope redactedDebug = policy.redact(
                event,
                ExecutionContext.of("tenant-1", "actor-1").withRoles(Set.of("ADMIN", "DEBUG"))
        );
        assertEquals("OK", redactedDebug.payload().get("status"));
        assertEquals("u-1", redactedDebug.payload().get("entityId"));
        assertFalse(redactedDebug.payload().containsKey("secret"));
        assertFalse(redactedDebug.payload().containsKey("token"));
    }
}
