package com.npdev.adapters.authcontext.jwt;

import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticatedContextResolverTest {

    @Test
    void resolvesTenantActorAndRolesFromClaims() {
        JwtAuthenticatedContextResolver resolver = new JwtAuthenticatedContextResolver();
        ExecutionContext context = resolver.resolveFromPrincipal(
                Map.of(
                        "tenant_id", "tenant-a",
                        "sub", "user-1",
                        "roles", List.of("admin", "debug")
                ),
                Map.of()
        );

        assertEquals("tenant-a", context.tenantId());
        assertEquals("user-1", context.actorId());
        assertTrue(context.hasRole("ADMIN"));
        assertTrue(context.hasRole("DEBUG"));
    }

    @Test
    void mapsTagHeadersToContextTags() {
        JwtAuthenticatedContextResolver resolver = new JwtAuthenticatedContextResolver();
        ExecutionContext context = resolver.resolveFromPrincipal(
                Map.of("tenant_id", "tenant-a", "sub", "user-1", "roles", "user"),
                Map.of("X-Tag-Source", "ui", "X-Tag-Region", "us")
        );

        assertEquals("ui", context.tags().get("Source"));
        assertEquals("us", context.tags().get("Region"));
    }

    @Test
    void ignoresRoleAndTenantHeadersAndUsesClaimsAsSourceOfTruth() {
        JwtAuthenticatedContextResolver resolver = new JwtAuthenticatedContextResolver();
        ExecutionContext context = resolver.resolveFromPrincipal(
                Map.of("tenant_id", "tenant-a", "actor_id", "actor-a", "roles", List.of("operator")),
                Map.of("X-Roles", "ADMIN", "X-Tenant-Id", "tenant-b", "X-Actor-Id", "actor-b")
        );

        assertEquals("tenant-a", context.tenantId());
        assertEquals("actor-a", context.actorId());
        assertTrue(context.hasRole("OPERATOR"));
        assertFalse(context.hasRole("ADMIN"));
    }
}
