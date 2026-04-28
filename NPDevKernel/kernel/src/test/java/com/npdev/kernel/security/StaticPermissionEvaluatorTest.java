package com.npdev.kernel.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticPermissionEvaluatorTest {

    @Test
    void shouldAllowDirectPermission() {
        StaticPermissionEvaluator evaluator = new StaticPermissionEvaluator(List.of());

        PermissionDecision decision = evaluator.evaluate(
                new PermissionSubject("alice", "tenant-a", List.of("user"), List.of("flow.execute")),
                new PermissionRequirement("flow.execute", "flow", "createuserflow")
        );

        assertTrue(decision.allowed());
    }

    @Test
    void shouldAllowManifestGrantByRoleAndTenant() {
        StaticPermissionEvaluator evaluator = new StaticPermissionEvaluator(List.of(
                new PermissionGrant("capability.invoke", "tenant-a", "", "admin")
        ));

        PermissionDecision decision = evaluator.evaluate(
                new PermissionSubject("bob", "tenant-a", List.of("admin"), List.of()),
                new PermissionRequirement("capability.invoke", "capability", "emailcapability")
        );

        assertTrue(decision.allowed());
    }

    @Test
    void shouldDenyWhenNoGrantMatches() {
        StaticPermissionEvaluator evaluator = new StaticPermissionEvaluator(List.of(
                new PermissionGrant("flow.execute", "tenant-a", "alice", "")
        ));

        PermissionDecision decision = evaluator.evaluate(
                new PermissionSubject("charlie", "tenant-b", List.of("user"), List.of()),
                new PermissionRequirement("flow.execute", "flow", "createuserflow")
        );

        assertFalse(decision.allowed());
    }
}
