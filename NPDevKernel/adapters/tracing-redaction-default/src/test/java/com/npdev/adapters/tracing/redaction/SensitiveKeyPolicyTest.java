package com.npdev.adapters.tracing.redaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0009 / P2: proves the single shared classifier every Default*RedactionPolicy now delegates
 * to, including the "authorization" case that DefaultTraceRedactionPolicy's own former private
 * copy silently omitted before this consolidation.
 */
class SensitiveKeyPolicyTest {

    // R80 (ledger/items/REG-80.yml): modelSensitiveFieldNames is a static/shared registry --
    // every test that registers something must clear it again so test order can never matter.
    @AfterEach
    void resetModelSensitiveFieldNames() {
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(null);
    }

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

    @Test
    void registeredModelFieldNameIsFlaggedExactlyNotByRandomSubstring() {
        assertFalse(SensitiveKeyPolicy.isSensitiveKey("customerEmail"),
                "not sensitive before registration -- no static substring matches it");
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(List.of("customerEmail"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("customerEmail"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("CUSTOMEREMAIL"), "case-insensitive, like the static denylist");
        assertFalse(SensitiveKeyPolicy.isSensitiveKey("customerEmailVerified"),
                "exact match only -- registering one field must not substring-match a longer key");
    }

    @Test
    void registeredModelFieldNamesAreOrredWithTheStaticDenylistNotAReplacement() {
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(List.of("customerEmail"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("customerEmail"), "model-declared field still flagged");
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("password"), "static denylist still active alongside it");
    }

    @Test
    void clearingRegistrationWithNullOrEmptyRestoresPriorBehavior() {
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(List.of("customerEmail"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("customerEmail"));
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(List.of());
        assertFalse(SensitiveKeyPolicy.isSensitiveKey("customerEmail"));
    }

    // Move 8 (item G7): pins the deliberate fail-safe direction -- this registry is a flat
    // Set<String> of field NAMES with no concept scoping, so registering a field name from one
    // concept redacts that key name everywhere, regardless of which concept declared it sensitive.
    @Test
    void registeringAFieldNameRedactsThatKeyRegardlessOfOriginatingConcept() {
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(List.of("email"));
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("email"),
                "registered by one concept's field.sensitive declaration, but must redact the key name globally");
    }
}
