package com.npdev.kernel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pigmentampas friction #37: {@link ExecutionContext#isSystem} (the model's {@code $user.isSystem})
 * is decided by instance identity, so only principals the platform itself created qualify.
 */
class ExecutionContextSystemTest {

    @Test
    void schedulerAndSeedingContextsAreSystem() {
        assertTrue(ExecutionContext.isSystem(ExecutionContext.system("acme")));
        assertTrue(ExecutionContext.isSystem(ExecutionContext.seeding("acme")));
    }

    @Test
    void schedulerContextIsOneInstancePerTenant() {
        assertSame(ExecutionContext.system("acme"), ExecutionContext.system("acme"));
        assertTrue(ExecutionContext.isSystem(ExecutionContext.system("other-tenant")));
    }

    @Test
    void lookalikesCopiesAndResumedFlowsAreNotSystem() {
        assertFalse(ExecutionContext.isSystem(
                new ExecutionContext("acme", "system:scheduler", Map.of("trigger", "schedule"), Set.of("ADMIN"))));
        assertFalse(ExecutionContext.isSystem(ExecutionContext.system("acme").withRoles(Set.of("ADMIN"))));
        assertFalse(ExecutionContext.isSystem(ExecutionContext.resuming("acme", "system:scheduler")));
        assertFalse(ExecutionContext.isSystem(ExecutionContext.of("acme", "tito")));
        assertFalse(ExecutionContext.isSystem(ExecutionContext.anonymous()));
        assertFalse(ExecutionContext.isSystem(null));
    }
}
