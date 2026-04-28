package com.npdev.kernel;

import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KernelRunnerPermissionEnforcementTest {

    @Test
    void shouldDenyFlowExecutionWhenPermissionIsMissing() {
        KernelRunner runner = new KernelRunner(
                event -> {
                },
                (entityName, payload) -> List.of(),
                new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.returnValue("return-user", "$input"))
                )),
                (call, state) -> CapabilityResult.success(state)
        ).withPermissionEvaluator(new StaticPermissionEvaluator(List.of()));

        ExecutionResult result = runner.execute(
                "CreateUser",
                Map.of("email", "a@b.com"),
                ExecutionContext.of("tenant-a", "alice").withRoles(Set.of("USER"))
        );

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertNotNull(result.getFailureInfo());
        //assertEquals("forbidden", result.getFailureInfo().code());
        assertEquals("forbidden", result.getFailureInfo().code());
    }

    @Test
    void shouldDenyCapabilityInvocationWhenPermissionIsMissing() {
        KernelRunner runner = new KernelRunner(
                event -> {
                },
                (entityName, payload) -> List.of(),
                new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "persist",
                                        "PersistenceCapability",
                                        "CrudCapability",
                                        "test-adapter",
                                        "save",
                                        List.of("$input"),
                                        "$saved"
                                ),
                                FlowStepDefinition.returnValue("return-user", "$saved")
                        )
                )),
                (call, state) -> CapabilityResult.success(Map.of("id", "u-1"))
        ).withPermissionEvaluator(new StaticPermissionEvaluator(List.of(
                new PermissionGrant("flow.execute", "tenant-a", "", "admin")
        )));

        ExecutionResult result = runner.execute(
                "CreateUser",
                Map.of("email", "a@b.com"),
                ExecutionContext.of("tenant-a", "alice").withRoles(Set.of("ADMIN"))
        );

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals("CAPABILITY_PERMISSION_DENIED", result.getCapabilityError().code());
        assertEquals(CapabilityErrorKind.AUTH, result.getCapabilityError().kind());
    }
}
