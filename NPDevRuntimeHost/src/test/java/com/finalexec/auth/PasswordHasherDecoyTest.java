package com.finalexec.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-18 (REG-16 finding F1): the login timing side-channel fix. {@link LoginController}'s
 * unknown-user / missing-credential paths must spend the same PBKDF2 work as the wrong-password path,
 * or response latency reveals which usernames exist. {@link PasswordHasher#verifyDecoy} is the shared
 * "always do the work, always deny" primitive those paths route through; this pins its two properties.
 */
class PasswordHasherDecoyTest {

    @Test
    void verifyDecoyAlwaysDenies() {
        assertFalse(PasswordHasher.verifyDecoy("any-password"), "decoy verification must never authenticate");
        assertFalse(PasswordHasher.verifyDecoy(""), "decoy verification must never authenticate");
        assertFalse(PasswordHasher.verifyDecoy(null), "decoy verification must never authenticate");
    }

    @Test
    void verifyDecoyActuallyPerformsThePbkdf2Work() {
        // Warm up (JIT + one-time decoy-hash class init) so the timing below reflects steady-state work.
        String realHash = PasswordHasher.hash("real-password");
        PasswordHasher.verify("real-password", realHash);
        PasswordHasher.verifyDecoy("wrong-password");

        long realNanos = minNanos(() -> PasswordHasher.verify("real-password", realHash));
        long decoyNanos = minNanos(() -> PasswordHasher.verifyDecoy("wrong-password"));

        // Both do one 210k-iteration PBKDF2, so their durations are inherently within a small factor
        // of each other REGARDLESS of machine speed. A short-circuited decoy (the bug) would be orders
        // of magnitude faster. A ratio floor is therefore stable across CI hardware where an absolute
        // millisecond threshold would not be.
        assertTrue(decoyNanos * 4 >= realNanos,
                "decoy verification (" + decoyNanos + "ns) must do PBKDF2 work comparable to a real verify ("
                        + realNanos + "ns); a much faster decoy means it short-circuited");
    }

    private static long minNanos(Runnable action) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            action.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }
}
