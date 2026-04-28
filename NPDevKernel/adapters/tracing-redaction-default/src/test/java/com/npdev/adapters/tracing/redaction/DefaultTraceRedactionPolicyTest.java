package com.npdev.adapters.tracing.redaction;

import com.npdev.kernel.CapabilityError;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTraceRedactionPolicyTest {

    @Test
    void redactsSensitiveInfoAndDropsNonAllowlistedKeys() {
        DefaultTraceRedactionPolicy policy = new DefaultTraceRedactionPolicy();
        FlowTrace trace = new FlowTrace(
                new FlowTraceMeta("exec-1", "corr-1", "CreateUser", "tenant-1", "actor-1", Map.of()),
                1000L,
                1100L,
                StepOutcome.FAILED,
                List.of(new StepTrace(
                        0,
                        "save-user",
                        "CAPABILITY_CALL",
                        1000L,
                        1050L,
                        StepOutcome.FAILED,
                        Map.of(
                                "adapterId", "inmemory",
                                "writtenStateKeys", List.of("saved"),
                                "email", "ana@acme.com",
                                "token", "abc123",
                                "debugPayload", Map.of("password", "pw")
                        ),
                        List.of(new InvariantEngine.Violation(
                                "INVARIANT_FAIL",
                                "email ana@acme.com is invalid",
                                "EmailRequired",
                                "User",
                                "CreateUser",
                                "save-user",
                                0,
                                Map.of("raw", "should-not-leak")
                        )),
                        new CapabilityError(
                                "CAPABILITY_CONTRACT_VIOLATION",
                                "invalid token abc",
                                CapabilityErrorKind.CONTRACT,
                                Map.of("token", "abc")
                        )
                ))
        );

        FlowTrace redacted = policy.redact(trace, ExecutionContext.of("tenant-1", "actor-1"));

        StepTrace step = redacted.steps().get(0);
        assertEquals("inmemory", step.info().get("adapterId"));
        assertEquals(List.of("saved"), step.info().get("writtenStateKeys"));
        assertFalse(step.info().containsKey("email"));
        assertFalse(step.info().containsKey("token"));
        assertFalse(step.info().containsKey("debugPayload"));

        InvariantEngine.Violation violation = step.invariantViolations().get(0);
        assertEquals("***", violation.message());
        assertTrue(violation.details().isEmpty());

        assertEquals("CAPABILITY_CONTRACT_VIOLATION", step.capabilityError().code());
        assertEquals("invalid token abc", step.capabilityError().message());
        assertTrue(step.capabilityError().details().isEmpty());
    }

    @Test
    void debugRoleKeepsAllowlistedDebugInfoAndStillMasksSecrets() {
        DefaultTraceRedactionPolicy policy = new DefaultTraceRedactionPolicy();
        FlowTrace trace = new FlowTrace(
                new FlowTraceMeta("exec-2", "corr-2", "CreateInvoice", "tenant-1", "actor-1", Map.of()),
                1000L,
                1100L,
                StepOutcome.FAILED,
                List.of(new StepTrace(
                        0,
                        "dispatch",
                        "CAPABILITY_CALL",
                        1000L,
                        1050L,
                        StepOutcome.FAILED,
                        Map.of(
                                "capName", "persistence",
                                "entityId", "inv-1",
                                "payloadPreview", "email@masked.com",
                                "token", "secret-value"
                        ),
                        List.of(),
                        new CapabilityError(
                                "CAPABILITY_FAILED",
                                "failed",
                                CapabilityErrorKind.PERMANENT,
                                Map.of("reason", "network", "token", "abc")
                        )
                ))
        );

        FlowTrace redacted = policy.redact(
                trace,
                ExecutionContext.of("tenant-1", "actor-1").withRoles(Set.of("DEBUG"))
        );

        StepTrace step = redacted.steps().get(0);
        assertEquals("persistence", step.info().get("capName"));
        assertEquals("inv-1", step.info().get("entityId"));
        assertEquals("***", step.info().get("payloadPreview"));
        assertFalse(step.info().containsKey("token"));
        assertEquals("network", step.capabilityError().details().get("reason"));
        assertEquals("***", step.capabilityError().details().get("token"));
    }
}
