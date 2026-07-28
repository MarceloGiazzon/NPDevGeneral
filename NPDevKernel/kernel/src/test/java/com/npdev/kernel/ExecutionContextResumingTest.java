package com.npdev.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * REG-56: {@link ExecutionContext#resuming} pins the fix. Before this existed, a flow resuming
 * across a real JVM restart lost the ADMIN-only "capability.invoke" permission its own capabilityCall
 * step needed -- reproduced live (docs/NPDEV_OPEN_ITEMS_REGISTER.md): the scheduled sweep resumed it
 * via {@link ExecutionContext#of}, which always defaults role to USER regardless of the flow's
 * original submitter; a fresh JVM's event-driven resume used whichever caller happened to publish
 * the awaited event, denying or over-granting access depending on who that was. Neither preserved the
 * flow's OWN authorization level. #resuming grants the trusted resume-level role (mirroring
 * ExecutionContext#system's already-established ADMIN trust for the cron scheduler, LNCH-12) while
 * keeping the instance's own actor/tenant for audit traceability.
 */
class ExecutionContextResumingTest {

    @Test
    void grantsAdminRoleRegardlessOfOriginalActor() {
        ExecutionContext resumed = ExecutionContext.resuming("acme", "developer");
        assertTrue(resumed.hasRole("ADMIN"), "a resumed flow must be trusted to complete its own already-authorized steps");
    }

    @Test
    void preservesTenantAndActorForAuditTraceability() {
        ExecutionContext resumed = ExecutionContext.resuming("acme", "developer");
        assertEquals("acme", resumed.tenantId());
        assertEquals("developer", resumed.actorId());
    }

    @Test
    void differsFromOfWhichDefaultsToUserOnly() {
        // The regression this test exists to catch: silently reverting ResumeCoordinator back to
        // ExecutionContext#of would compile fine and only fail on a real capability-gated resume.
        ExecutionContext viaOf = ExecutionContext.of("acme", "developer");
        assertTrue(viaOf.hasRole("USER"));
        assertTrue(!viaOf.hasRole("ADMIN"), "ExecutionContext#of must stay USER-only -- #resuming exists precisely because #of is not enough for resume");
    }
}
