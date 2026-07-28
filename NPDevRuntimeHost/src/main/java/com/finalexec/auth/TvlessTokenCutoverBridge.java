package com.finalexec.auth;

import com.npdev.runtime.support.IdentityRoleLookup;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * REG-23: bridges the Spring property {@code npdev.auth.jwt.reject-tokens-without-tv-after} to the JVM
 * system property that the shared {@link IdentityRoleLookup#isTokenRevoked} decision point reads.
 *
 * <p>The revocation decision must be byte-identical on BOTH claim->context paths (RuntimeHost
 * {@code IdentityAwareContextResolver} and the kernel adapter {@code GeneratedCrudRuntimeSupport}); the
 * kernel adapter has no Spring config access, so RuntimeHost bridges the value once at boot. Fails fast
 * if the value is set but not a valid ISO-8601 instant, so a misconfiguration can never silently reject
 * (or silently fail to reject) every legacy token.</p>
 */
@Component
public class TvlessTokenCutoverBridge {

    public TvlessTokenCutoverBridge(
            @Value("${npdev.auth.jwt.reject-tokens-without-tv-after:}") String rejectAfter) {
        if (rejectAfter == null || rejectAfter.isBlank()) {
            System.clearProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY);
            return;
        }
        String trimmed = rejectAfter.trim();
        try {
            Instant.parse(trimmed);
        } catch (DateTimeParseException malformed) {
            throw new IllegalStateException(
                    "npdev.auth.jwt.reject-tokens-without-tv-after must be an ISO-8601 instant "
                    + "(e.g. 2026-08-01T00:00:00Z); got: '" + trimmed + "'. "
                    + "See docs/CONFIGURATION.md#authentication.");
        }
        System.setProperty(IdentityRoleLookup.REJECT_TVLESS_AFTER_PROPERTY, trimmed);
    }
}
