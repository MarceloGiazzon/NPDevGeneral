package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PK-5 step 2: the pure guard logic. Live proof that DENIED actually stops a real fetch attempt
 *  (vs. just throwing in a unit test) lives in NetworkPolicyGuardLiveTest. */
class NetworkPolicyTest {

    @Test
    void deniedRefusesWithAClearMessage() {
        NetworkPolicyViolationException failure = assertThrows(NetworkPolicyViolationException.class,
                () -> NetworkPolicy.DENIED.requireAllowed("fetch pack from oci://ghcr.io/x/y:1.0"));
        assertTrue(failure.getMessage().contains("oci://ghcr.io/x/y:1.0"), failure.getMessage());
        assertTrue(failure.getMessage().toLowerCase().contains("pack add"), failure.getMessage());
    }

    @Test
    void allowedNeverThrows() {
        assertDoesNotThrow(() -> NetworkPolicy.ALLOWED.requireAllowed("fetch pack from oci://ghcr.io/x/y:1.0"));
    }

    @Test
    void isAllowedReflectsConstruction() {
        assertFalse(NetworkPolicy.DENIED.isAllowed());
        assertTrue(NetworkPolicy.ALLOWED.isAllowed());
    }
}
