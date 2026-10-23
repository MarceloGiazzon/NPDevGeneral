package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REG-301: a named query's permissionRequirements[] are enforced, every one of them, fail-closed. */
class NamedQueryPermissionsTest {

    private static CompiledQuery query(List<String> requirements) {
        return new CompiledQuery("FinanceTotals", "Invoice", null, List.of(), null, List.of(),
                requirements, null, Map.of(), List.of(), List.of(), null);
    }

    private static final StaticPermissionEvaluator EVALUATOR = new StaticPermissionEvaluator(List.of(
            new PermissionGrant("report.finance", "", "", "admin"),
            new PermissionGrant("update:invoice", "", "", "admin"),
            new PermissionGrant("update:invoice", "", "", "clerk")));

    private static ExecutionContext caller(String... roles) {
        return ExecutionContext.of("tenant-a", "tito").withRoles(Set.of(roles));
    }

    @Test
    void aQueryWithNoRequirementsIsOpenEvenWithoutAnEvaluator() {
        assertEquals(Optional.empty(), NamedQueryPermissions.denial(query(List.of()), caller("user"), null));
    }

    @Test
    void aCallerLackingTheRequirementIsDenied() {
        Optional<String> denied = NamedQueryPermissions.denial(query(List.of("report.finance")), caller("user"), EVALUATOR);
        assertEquals(Optional.of("Query 'FinanceTotals' requires permission 'report.finance'."), denied);
        assertEquals(Optional.empty(),
                NamedQueryPermissions.denial(query(List.of("report.finance")), caller("admin"), EVALUATOR));
    }

    @Test
    void everyRequirementMustBeHeldNotJustOne() {
        CompiledQuery both = query(List.of("update:invoice", "report.finance"));
        assertTrue(NamedQueryPermissions.denial(both, caller("clerk"), EVALUATOR).isPresent());
        assertEquals(Optional.empty(), NamedQueryPermissions.denial(both, caller("admin"), EVALUATOR));
    }

    @Test
    void declaredRequirementsWithNoEvaluatorFailClosed() {
        assertTrue(NamedQueryPermissions.denial(query(List.of("report.finance")), caller("admin"), null).isPresent());
    }
}
