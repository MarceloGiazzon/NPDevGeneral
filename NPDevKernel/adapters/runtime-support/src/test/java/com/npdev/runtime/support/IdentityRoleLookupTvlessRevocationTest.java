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
        assertFalse(IdentityRoleLookup.isTokenRevoked(null, null, null, "acme", "alice"),
                "tv-less token must stay accepted when the cutover is unset (backward compatible)");
    }

    @Test
    void tvlessTokenIsRevokedAfterPastCutover() {
        System.setProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY,
                Instant.now().minus(1, ChronoUnit.HOURS).toString());
        assertTrue(IdentityRoleLookup.isTokenRevoked(null, null, null, "acme", "alice"),
                "tv-less token must be revoked once the cutover instant has passed");
    }

    @Test
    void tvlessTokenIsNotRevokedBeforeFutureCutover() {
        System.setProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY,
                Instant.now().plus(1, ChronoUnit.HOURS).toString());
        assertFalse(IdentityRoleLookup.isTokenRevoked(null, null, null, "acme", "alice"),
                "tv-less token must stay accepted until the cutover instant is reached");
    }

    /**
     * REG-291 (X0 rule): a tv claim that is present but not an integer cannot match any stored
     * token_version, so the token is revoked -- it used to be answered "not revoked". Independent of
     * the tv-less cutover: unset here, which is the lenient setting.
     */
    @Test
    void nonNumericTvClaimIsRevokedNotWavedThrough() {
        System.clearProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY);
        assertTrue(IdentityRoleLookup.isTokenRevoked("abc", null, null, "acme", "alice"),
                "a non-numeric tv claim must be treated as revoked");
        assertTrue(IdentityRoleLookup.isTokenRevoked("", null, null, "acme", "alice"),
                "a blank tv claim must be treated as revoked");
        // Control: a well-formed tv equal to the stored version (0 with no DataSource) is not revoked.
        assertFalse(IdentityRoleLookup.isTokenRevoked(0, null, null, "acme", "alice"));
    }

    @Test
    void malformedCutoverFailsOpen() {
        System.setProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY, "not-a-date");
        assertFalse(IdentityRoleLookup.isTokenRevoked(null, null, null, "acme", "alice"),
                "a malformed cutover must fail OPEN here (StartupValidator/bridge rejects it at boot)");
    }
}
