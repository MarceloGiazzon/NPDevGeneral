package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-239 (B28 lift, D1 Phase 2): the guard for this policy observing a live model reload. Every
 * assertion below uses the SAME policy instance across the reload -- reconstructing it would prove
 * nothing, since the bean a running app holds is never reconstructed without a restart, which is
 * precisely the residual this closes.
 */
class DefaultExecutionAuthorizationPolicyModelReloadTest {

    private static CompiledModel compiledModelWithRoles(CompiledRole... roles) {
        return new CompiledModel(
                "ns", "2.0", "1.0.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null,
                List.of(roles)
        );
    }

    private static DefaultExecutionAuthorizationPolicy policyFor(CompiledModel model) {
        return new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy(), model);
    }

    private static ExecutionContext auditorIn(String tenantId) {
        return ExecutionContext.of(tenantId, "actor-a").withRoles(Set.of("AUDITOR"));
    }

    @Test
    void aGrantAddedToAnAppDeclaredRoleByAHotReloadTakesEffectWithoutRebuildingThePolicy() {
        DefaultExecutionAuthorizationPolicy policy = policyFor(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_EXECUTIONS"))));
        ExecutionContext auditor = auditorIn("tenant-a");

        assertTrue(policy.canListExecutions(auditor, "tenant-a"), "pre-reload: the declared grant holds");
        assertFalse(policy.canReadAudit(auditor), "pre-reload: READ_AUDIT was never granted");

        policy.setModel(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_EXECUTIONS", "READ_AUDIT"))));

        assertTrue(policy.canReadAudit(auditor),
                "a grant added by the reloaded model must be honored by the SAME instance, no restart");
        assertTrue(policy.canListExecutions(auditor, "tenant-a"), "post-reload: the pre-existing grant survives");
    }

    @Test
    void aGrantRemovedByAHotReloadStopsBeingHonoredWithoutRebuildingThePolicy() {
        DefaultExecutionAuthorizationPolicy policy = policyFor(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_EXECUTIONS", "READ_AUDIT"))));
        ExecutionContext auditor = auditorIn("tenant-a");

        assertTrue(policy.canReadAudit(auditor));

        policy.setModel(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_EXECUTIONS"))));

        assertFalse(policy.canReadAudit(auditor),
                "revoking a grant must take effect on the next check -- a stale cache here is a "
                        + "privilege the operator believes they already removed");
    }

    @Test
    void aRoleDroppedEntirelyByAHotReloadStopsBeingHonored() {
        DefaultExecutionAuthorizationPolicy policy = policyFor(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_EXECUTIONS"))));
        ExecutionContext auditor = auditorIn("tenant-a");

        assertTrue(policy.canListExecutions(auditor, "tenant-a"));

        policy.setModel(compiledModelWithRoles());

        assertFalse(policy.canListExecutions(auditor, "tenant-a"),
                "a role no longer declared by the live model grants nothing");
    }

    @Test
    void aReloadedModelWithAnUnrecognizedGrantFailsTheReloadItselfRatherThanEveryLaterRequest() {
        // setModel is called from a ModelHolder.ModelReloadListener, inside swap()'s write lock --
        // throwing here is how a bad reload is reported as a FAILED RELOAD, matching the fail-loud
        // -at-startup contract the three-arg constructor's javadoc establishes. The pre-reload
        // state must survive intact, so the app keeps serving the last model that did validate.
        DefaultExecutionAuthorizationPolicy policy = policyFor(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_AUDIT"))));
        ExecutionContext auditor = auditorIn("tenant-a");

        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> policy.setModel(
                compiledModelWithRoles(new CompiledRole("AUDITOR", List.of("READ_AUDIT", "NOT_A_PERMISSION")))));
        assertTrue(rejected.getMessage().contains("NOT_A_PERMISSION"),
                "the diagnostic must name the offending grant: " + rejected.getMessage());

        assertTrue(policy.canReadAudit(auditor),
                "a rejected reload leaves the previously-validated permission set in place");
    }

    @Test
    void reloadingToANullModelLeavesNoAppDeclaredRolesRatherThanThrowing() {
        // The constructors already document null as "no app-declared roles"; setModel must agree,
        // not NPE, so the reload path has the same tolerance the boot path has.
        DefaultExecutionAuthorizationPolicy policy = policyFor(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_AUDIT"))));
        ExecutionContext auditor = auditorIn("tenant-a");

        policy.setModel(null);

        assertFalse(policy.canReadAudit(auditor));
    }

    @Test
    void permissionsUnrelatedToAppDeclaredRolesAreUnaffectedByAReload() {
        // Built-in roles resolve through RolePermissions, not the app-declared map, so a reload
        // that changes roles[] must not perturb them.
        DefaultExecutionAuthorizationPolicy policy = policyFor(compiledModelWithRoles(
                new CompiledRole("AUDITOR", List.of("READ_AUDIT"))));
        ExecutionContext operator = ExecutionContext.of("tenant-a", "actor-b").withRoles(Set.of("OPERATOR"));

        boolean beforeReload = policy.canExecuteFlow(operator, "CreateUser");

        policy.setModel(compiledModelWithRoles(new CompiledRole("AUDITOR", List.of("READ_EXECUTIONS"))));

        assertEquals(beforeReload, policy.canExecuteFlow(operator, "CreateUser"),
                "a built-in role's permissions do not come from roles[] and must not shift on reload");
    }
}
