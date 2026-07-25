package com.npdev.kernel.capability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REG-36. The encoding's properties, stated as tests rather than left to the javadoc. */
class IdempotencyKeysTest {

    @Test
    void ordinaryKeysAreStoredUnchanged() {
        // Backward compatibility is the whole reason short keys are not also rewritten: every
        // idempotency record written before this change has to stay findable.
        assertEquals("order-42", IdempotencyKeys.bound("order-42"));
        assertEquals("a".repeat(IdempotencyKeys.MAX_CHARS), IdempotencyKeys.bound("a".repeat(IdempotencyKeys.MAX_CHARS)));
        assertNull(IdempotencyKeys.bound(null));
        assertEquals("   ", IdempotencyKeys.bound("   "));
    }

    @Test
    void anOversizedKeyIsDigestedToAFixedShortForm() {
        String bounded = IdempotencyKeys.bound("x".repeat(100_000));

        assertTrue(bounded.startsWith(IdempotencyKeys.DIGEST_PREFIX));
        assertEquals(IdempotencyKeys.DIGEST_PREFIX.length() + 64, bounded.length());
        assertTrue(bounded.length() <= IdempotencyKeys.MAX_CHARS,
                "the bounded form must itself be within the bound, or the fix does not fix anything");
    }

    @Test
    void twoDistinctOversizedKeysDoNotCollapseIntoOneRecord() {
        // The register's acceptance criterion for REG-36. Digesting is only safe if it preserves
        // distinctness -- otherwise the fix trades a crash for two callers sharing a cached result.
        String first = "x".repeat(50_000) + "-alpha";
        String second = "x".repeat(50_000) + "-beta";

        assertNotEquals(IdempotencyKeys.bound(first), IdempotencyKeys.bound(second));
    }

    @Test
    void aShortKeyCannotBeForgedToImpersonateADigestedOne() {
        // The collision the naive "just hash long keys" fix introduces: if an oversized key X were
        // stored as sha256(X), a caller submitting the LITERAL short string sha256(X) would land on
        // X's record and be served someone else's cached result. Prefixed short keys are therefore
        // digested too, so every stored key starting with the prefix is a digest of its own input.
        String oversized = "x".repeat(100_000);
        String storedForOversized = IdempotencyKeys.bound(oversized);

        String forgery = IdempotencyKeys.bound(storedForOversized);

        assertNotEquals(storedForOversized, forgery,
                "submitting the digested form as a literal key must NOT resolve to the digested record");
        assertTrue(forgery.startsWith(IdempotencyKeys.DIGEST_PREFIX));
    }

    @Test
    void boundingIsStableAcrossCalls() {
        // find() and saveSuccess() bound independently; if the function were not deterministic, a
        // write would never be findable by the read that follows it.
        String oversized = "y".repeat(9_999);
        assertEquals(IdempotencyKeys.bound(oversized), IdempotencyKeys.bound(oversized));
    }

    @Test
    void theDigestPrefixIsNotSomethingBoundingCanProduceByAccident() {
        assertFalse(IdempotencyKeys.bound("order-42").startsWith(IdempotencyKeys.DIGEST_PREFIX));
    }
}
