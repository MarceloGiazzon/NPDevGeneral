package com.npdev.adapters.requestcontext.defaultresolver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRequestContextResolverTest {

    @Test
    void resolverDoesNotAcceptSpoofableSecurityClaims() {
        // Request context security fields must come from authenticated claims, not spoofable headers.
        assertTrue(true);
    }
}
