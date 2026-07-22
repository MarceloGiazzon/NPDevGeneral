package com.finalexec.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-19 (REG-16 finding F2): {@link LoginThrottle}'s failed-attempt map must not grow without bound.
 * An unauthenticated attacker controls the (tenant, username) key space, so failed logins with a
 * flood of distinct usernames would otherwise accumulate {@code Window} entries until the JVM runs out
 * of memory. This proves the map stays bounded even under a same-window spray (no time advance, so
 * nothing expires -- the hard cap is the only thing that can bound it).
 */
class LoginThrottleBoundedTest {

    @Test
    void mapStaysBoundedUnderDistinctUsernameSpray() {
        LoginThrottle throttle = new LoginThrottle(Clock.fixed(Instant.now(), ZoneOffset.UTC));

        // Spray well past the cap with all-distinct usernames, all inside one (frozen) window so none
        // can expire. Before REG-19 this grew the map to `sprayCount`; after, it is capped.
        int sprayCount = LoginThrottle.MAX_TRACKED_KEYS * 3;
        for (int i = 0; i < sprayCount; i++) {
            throttle.recordFailure("tenant", "user-" + i);
        }

        assertTrue(throttle.trackedKeyCount() <= LoginThrottle.MAX_TRACKED_KEYS,
                "throttle map must stay bounded (<= " + LoginThrottle.MAX_TRACKED_KEYS + ") under a "
                        + sprayCount + "-distinct-username spray, but held " + throttle.trackedKeyCount());
    }

    @Test
    void expiredSprayIsSweptSoALiveLockoutSurvives() {
        // The common case: an old distinct-username spray fills the map, then its windows expire; a
        // legitimate user locked out AFTER the spray must keep their lockout while the expired spray
        // is reclaimed. Eviction drops expired windows before any live one.
        java.util.concurrent.atomic.AtomicLong millis = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        };
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i < LoginThrottle.MAX_TRACKED_KEYS; i++) {
            throttle.recordFailure("tenant", "old-" + i);
        }
        // Advance past the 15-minute window so the whole spray is now expired dead weight.
        millis.addAndGet(20L * 60 * 1000);
        // The victim's own failures push the map over the cap and trigger eviction, which sweeps the
        // expired spray first -- so the victim's live window is never at risk.
        for (int a = 0; a < LoginThrottle.MAX_ATTEMPTS; a++) {
            throttle.recordFailure("tenant", "victim");
        }

        assertTrue(throttle.isLocked("tenant", "victim"),
                "a user locked out after an expired spray must stay locked");
        assertTrue(throttle.trackedKeyCount() <= LoginThrottle.MAX_TRACKED_KEYS,
                "the expired spray must be reclaimed, keeping the map bounded");
    }
}
