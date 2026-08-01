package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRole;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultExecutionAuthorizationPolicyTest {

    private final DefaultExecutionAuthorizationPolicy policy = new DefaultExecutionAuthorizationPolicy();

    private static CompiledModel compiledModelWithRoles(CompiledRole... roles) {
        return new CompiledModel(
                "ns", "2.0", "1.0.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null,
                List.of(roles)
        );
    }

    @Test
    void deniesAnonymousDefaultRequester() {
        ExecutionContext anonymous = ExecutionContext.anonymous();
        assertFalse(policy.canExecuteFlow(anonymous, "CreateUser"));
        assertFalse(policy.canPublishEvent(anonymous, "UserCreated", "corr-1"));
        assertFalse(policy.canReadFailures(anonymous));
        assertFalse(policy.canReadAdminOps(anonymous));
    }

    @Test
    void deniesTenantIdDefaultEvenWithFullRolesAndPermissions(){
        // ARCH-15: "default" is a reserved sentinel meaning "no tenant registered" -- denial must
        // come from the tenantId itself, not merely from missing roles. A fully-privileged
        // requester registered under the literal tenantId "default" must still be denied.
        ExecutionContext requester = ExecutionContext.of("default", "actor-a").withRoles(Set.of("ADMIN"));
        assertFalse(policy.canExecuteFlow(requester, "CreateUser"));
        assertFalse(policy.canPublishEvent(requester, "UserCreated", "corr-1"));
        assertFalse(policy.canReadFailures(requester));
        assertFalse(policy.canReadAdminOps(requester));

        ExecutionContext caseInsensitive = ExecutionContext.of("DEFAULT", "actor-a").withRoles(Set.of("ADMIN"));
        assertFalse(policy.canExecuteFlow(caseInsensitive, "CreateUser"));
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

    @Test
    void reg45ResumeRequiresTheOriginatingActorNotJustTheTenant() {
        // REG-45. resumeExecution hands back the ExecutionResult, which carries the flow's accumulated
        // state -- records the flow read under the ORIGINAL actor's row-level access.read scope. So
        // "same tenant" was not a sufficient gate: a colleague holding RESUME_EXECUTIONS could resume
        // someone else's suspended flow and be handed data they could not have read directly.
        DefaultExecutionAuthorizationPolicy policy =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy());

        FlowInstance startedByA = FlowInstance.start(
                "exec-a", "FlowA", "corr-a", "tenant-a", "actor-a", Map.of(), 1000L);

        ExecutionContext actorA = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("OPERATOR"));
        ExecutionContext actorB = ExecutionContext.of("tenant-a", "actor-b").withRoles(Set.of("OPERATOR"));

        assertTrue(policy.canResumeExecution(actorA, startedByA),
                "the originating actor must still be able to resume their own flow");
        assertFalse(policy.canResumeExecution(actorB, startedByA),
                "a different actor in the SAME tenant must not resume it, even holding RESUME_EXECUTIONS");
    }

    @Test
    void reg45AnInstanceWithNoRecordedActorStaysTenantScopedOnly() {
        // FlowInstance normalises a blank actorId to null -- what a flow started anonymously, by the
        // cron scheduler, or before this field was populated looks like. Requiring equality against
        // null would make every one of those permanently unresumable, turning a data-scoping fix into
        // an availability regression for exactly the stuck flows an operator most needs to recover.
        DefaultExecutionAuthorizationPolicy policy =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy());

        FlowInstance ownerless = FlowInstance.start(
                "exec-c", "FlowC", "corr-c", "tenant-a", null, Map.of(), 1000L);

        ExecutionContext operator = ExecutionContext.of("tenant-a", "actor-b").withRoles(Set.of("OPERATOR"));
        ExecutionContext otherTenant = ExecutionContext.of("tenant-b", "actor-b").withRoles(Set.of("OPERATOR"));

        assertTrue(policy.canResumeExecution(operator, ownerless),
                "no recorded owner means there is no owner for actor-scoping to protect");
        assertFalse(policy.canResumeExecution(otherTenant, ownerless),
                "...but tenant isolation still applies");
    }

    /**
     * Wave 3 (RC-B1): GREEN proof -- before the {@code CompiledModel}-aware constructor existed, a
     * requester whose only role was an app-declared one (not USER/OPERATOR/ADMIN) was denied
     * EVERY permission with no way to grant it short of a platform code change.
     */
    @Test
    void appDeclaredRoleFromCompiledModelCanExecuteFlow() {
        CompiledModel compiledModel = compiledModelWithRoles(
                new CompiledRole("WAREHOUSE_MANAGER", List.of("EXECUTE_FLOW", "READ_EXECUTIONS")));
        DefaultExecutionAuthorizationPolicy policyWithRoles =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy(), compiledModel);

        ExecutionContext warehouseManager = ExecutionContext.of("tenant-a", "actor-a")
                .withRoles(Set.of("WAREHOUSE_MANAGER"));

        assertTrue(policyWithRoles.canExecuteFlow(warehouseManager, "PickOrder"));
        assertFalse(policyWithRoles.canReadAudit(warehouseManager),
                "the declared role only grants EXECUTE_FLOW/READ_EXECUTIONS, not READ_AUDIT");

        // Regression: the SAME requester against a policy built with no CompiledModel gets nothing,
        // proving the grant genuinely comes from the app-declared role, not some other change.
        assertFalse(policy.canExecuteFlow(warehouseManager, "PickOrder"));
    }

    /**
     * Wave 3 (RC-B1): a role declaring a grant name that is not a real {@link
     * com.npdev.kernel.auth.Permission} enum constant must fail loudly at construction (app boot),
     * not silently grant nothing the first time a warehouse-manager user makes a request.
     */
    @Test
    void unrecognizedGrantNameFailsAtConstructionNotAtRequestTime() {
        CompiledModel compiledModel = compiledModelWithRoles(
                new CompiledRole("WAREHOUSE_MANAGER", List.of("EXECUTE_FLOW", "TYPO_PERMISSION")));

        assertThrows(IllegalStateException.class,
                () -> new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy(), compiledModel));
    }

    /** Wave 3 (RC-B1) regression: a null CompiledModel (what every pre-existing call site passes,
     *  and what the two-arg constructor delegates to) behaves exactly like today. */
    @Test
    void nullCompiledModelBehavesLikeNoDeclaredRoles() {
        DefaultExecutionAuthorizationPolicy policyWithNullModel =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy(), null);
        ExecutionContext admin = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("ADMIN"));
        assertTrue(policyWithNullModel.canReadAudit(admin));
    }
}
