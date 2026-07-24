package com.npdev.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * REG-25: {@link ExecutionContext} is the single choke point every read and write derives its
 * tenant from, so canonicalizing tenantId here makes two casings of the same logical tenant land in
 * the SAME isolation bucket. Before REG-25 the compact constructor only trimmed the tenant, so
 * {@code Acme} and {@code acme} produced two distinct buckets (and diverged from the tenant
 * registry, which already lowercases on insert). These pin the on-write normalization contract.
 */
class ExecutionContextTenantCanonicalizationTest {

    @Test
    void mixedCaseTenantsConvergeToOneBucket() {
        ExecutionContext upper = ExecutionContext.of("Acme", "alice");
        ExecutionContext lower = ExecutionContext.of("acme", "bob");
        assertEquals("acme", upper.tenantId(), "tenantId must be canonicalized to lowercase on write");
        assertEquals(lower.tenantId(), upper.tenantId(),
                "two casings of the same logical tenant must resolve to one isolation bucket");
    }

    @Test
    void tenantIsTrimmedAndLowercased() {
        assertEquals("acme", ExecutionContext.of("  ACME  ", "alice").tenantId());
    }

    @Test
    void blankTenantStillDefaults() {
        assertEquals("default", ExecutionContext.of("   ", "alice").tenantId());
        assertEquals("default", ExecutionContext.of(null, "alice").tenantId());
    }

    @Test
    void reservedDefaultSentinelIsPreserved() {
        // REG-24: "default" is a reserved sentinel; canonicalization must leave it exactly "default".
        assertEquals("default", ExecutionContext.of("default", "alice").tenantId());
        assertEquals("default", ExecutionContext.of("DEFAULT", "alice").tenantId());
    }

    @Test
    void actorIdIsNotLowercased() {
        // Only tenantId is canonicalized to lowercase; actor identities stay case-sensitive.
        assertEquals("Alice", ExecutionContext.of("acme", "Alice").actorId());
        assertNotEquals("alice", ExecutionContext.of("acme", "Alice").actorId());
    }
}
