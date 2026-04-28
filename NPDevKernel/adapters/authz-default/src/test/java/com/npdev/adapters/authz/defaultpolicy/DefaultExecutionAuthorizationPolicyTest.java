package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultExecutionAuthorizationPolicyTest {

    private final DefaultExecutionAuthorizationPolicy policy = new DefaultExecutionAuthorizationPolicy();

    @Test
    void deniesAnonymousDefaultRequester() {
        ExecutionContext anonymous = ExecutionContext.anonymous();
        assertFalse(policy.canExecuteFlow(anonymous, "CreateUser"));
        assertFalse(policy.canPublishEvent(anonymous, "UserCreated", "corr-1"));
        assertFalse(policy.canReadFailures(anonymous));
        assertFalse(policy.canReadAdminOps(anonymous));
    }

    @Test
    void enforcesTenantIsolationAcrossTraceResumeAndSearch() {
        ExecutionContext requester = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("OPERATOR"));
        ExecutionContext otherTenant = ExecutionContext.of("tenant-b", "actor-b");

        FlowTrace sameTenantTrace = new FlowTrace(
                new FlowTraceMeta("exec-a", "corr-a", "FlowA", "tenant-a", "actor-a", Map.of()),
                1000L,
                1010L,
                StepOutcome.OK,
                List.of()
        );
        FlowTrace otherTenantTrace = new FlowTrace(
                new FlowTraceMeta("exec-b", "corr-b", "FlowB", "tenant-b", "actor-b", Map.of()),
                1000L,
                1010L,
                StepOutcome.OK,
                List.of()
        );

        FlowInstance sameTenantInstance = FlowInstance.start(
                "exec-a",
                "FlowA",
                "corr-a",
                "tenant-a",
                "actor-a",
                Map.of(),
                1000L
        );
        FlowInstance otherTenantInstance = new FlowInstance(
                "exec-b",
                "FlowB",
                "corr-b",
                "tenant-b",
                "actor-b",
                0,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "EventX",
                1000L,
                1000L
        );

        TraceQuery scopedQuery = new TraceQuery(null, null, null, null, null, 50, 0, "tenant-a", null);
        TraceQuery crossTenantQuery = new TraceQuery(null, null, null, null, null, 50, 0, "tenant-b", null);

        assertTrue(policy.canReadTrace(requester, sameTenantTrace));
        assertFalse(policy.canReadTrace(requester, otherTenantTrace));
        assertTrue(policy.canReadTrace(otherTenant, otherTenantTrace));

        assertTrue(policy.canResumeExecution(requester, sameTenantInstance));
        assertFalse(policy.canResumeExecution(requester, otherTenantInstance));

        assertTrue(policy.canSearchTraces(requester, scopedQuery));
        assertFalse(policy.canSearchTraces(requester, crossTenantQuery));

        assertTrue(policy.canReadExecution(requester, sameTenantInstance));
        assertFalse(policy.canReadExecution(requester, otherTenantInstance));

        assertTrue(policy.canListExecutions(requester, "tenant-a"));
        assertFalse(policy.canListExecutions(requester, "tenant-b"));

        assertTrue(policy.canReadEvents(requester, "tenant-a"));
        assertFalse(policy.canReadEvents(requester, "tenant-b"));
    }

    @Test
    void debugViewRequiresPrivilegedRoleAndTenant() {
        ExecutionContext user = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("USER"));
        ExecutionContext operator = user.withRoles(Set.of("OPERATOR"));
        ExecutionContext admin = user.withRoles(Set.of("USER", "ADMIN"));
        ExecutionContext missingTenant = ExecutionContext.of("", "actor-a").withRoles(Set.of("ADMIN"));

        assertFalse(policy.canUseDebugView(user));
        assertFalse(policy.canUseDebugView(operator));
        assertTrue(policy.canUseDebugView(admin));
        assertFalse(policy.canUseDebugView(missingTenant));
    }

    @Test
    void auditReadRequiresAdminRoleAndFailureReadSupportsOperator() {
        ExecutionContext user = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("USER"));
        ExecutionContext operator = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("OPERATOR"));
        ExecutionContext admin = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("ADMIN"));

        assertFalse(policy.canReadAudit(user));
        assertFalse(policy.canReadAudit(operator));
        assertTrue(policy.canReadAudit(admin));

        assertFalse(policy.canReadFailures(user));
        assertTrue(policy.canReadFailures(operator));
        assertTrue(policy.canReadFailures(admin));

        assertFalse(policy.canReadAdminOps(user));
        assertFalse(policy.canReadAdminOps(operator));
        assertTrue(policy.canReadAdminOps(admin));
    }
}
