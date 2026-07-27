package com.npdev.adapters.tracing.redaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0009 / P2: proves the single shared classifier every Default*RedactionPolicy now delegates
 * to, including the "authorization" case that DefaultTraceRedactionPolicy's own former private
 * copy silently omitted before this consolidation.
 */
class SensitiveKeyPolicyTest {

    @Test
    void classifiesKnownSensitiveSubstringsCaseInsensitively() {
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("password"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("apiToken"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("clientSecret"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("Authorization"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("AUTHORIZATION_HEADER"));
    }

    @Test
    void doesNotFlagBenignKeys() {
        assertFalse(SensitiveKeyPolicy.isSensitiveKey("entityId"));
        assertFalse(SensitiveKeyPolicy.isSensitiveKey(null));
        assertFalse(SensitiveKeyPolicy.isSensitiveKey("status"));
    }

    @Test
    void flagsEmailShapedValues() {
        assertTrue(SensitiveKeyPolicy.looksLikeSensitiveValue("ada@example.com"));
        assertFalse(SensitiveKeyPolicy.looksLikeSensitiveValue("plain text"));
        assertFalse(SensitiveKeyPolicy.looksLikeSensitiveValue(null));
    }
}
