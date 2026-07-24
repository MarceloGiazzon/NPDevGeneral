package com.npdev.runtime.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * REG-23: the config-driven tv-less-token branch of {@link IdentityRoleLookup#isTokenRevoked}. The
 * tv-less branch is DB-independent (it decides purely from the cutover property), so it is unit-testable
 * without a DataSource. Before REG-23 a tv-less token was ALWAYS accepted; these prove the cutover.
 */
class IdentityRoleLookupTvlessRevocationTest {

    @AfterEach
    void clearProp() {
        System.clearProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY);
    }

    @Test
    void tvlessTokenIsNotRevokedWhenCutoverUnset() {
        System.clearProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY);
        assertFalse(IdentityRoleLookup.isTokenRevoked(null, null, "acme", "alice"),
                "tv-less token must stay accepted when the cutover is unset (backward compatible)");
    }

    @Test
    void tvlessTokenIsRevokedAfterPastCutover() {
        System.setProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY,
                Instant.now().minus(1, ChronoUnit.HOURS).toString());
        assertTrue(IdentityRoleLookup.isTokenRevoked(null, null, "acme", "alice"),
                "tv-less token must be revoked once the cutover instant has passed");
    }

    @Test
    void tvlessTokenIsNotRevokedBeforeFutureCutover() {
        System.setProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY,
                Instant.now().plus(1, ChronoUnit.HOURS).toString());
        assertFalse(IdentityRoleLookup.isTokenRevoked(null, null, "acme", "alice"),
                "tv-less token must stay accepted until the cutover instant is reached");
    }

    @Test
    void malformedCutoverFailsOpen() {
        System.setProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY, "not-a-date");
        assertFalse(IdentityRoleLookup.isTokenRevoked(null, null, "acme", "alice"),
                "a malformed cutover must fail OPEN here (StartupValidator/bridge rejects it at boot)");
    }
}
