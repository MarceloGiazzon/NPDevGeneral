package com.finalexec.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-4: fixed-window login-attempt throttling. Uses a mutable {@link MutableClock} so the 15-minute
 * window boundary is exercised deterministically, without a real sleep.
 */
class LoginThrottleTest {

    @Test
    void tenLoginFailuresDoNotLockButTheEleventhDoes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i < LoginThrottle.MAX_ATTEMPTS; i++) {
            assertFalse(throttle.isLocked("acme", "alice"), "attempt " + (i + 1) + " must not be locked yet");
            throttle.recordFailure("acme", "alice");
        }
        assertTrue(throttle.isLocked("acme", "alice"), "the 11th attempt (MAX_ATTEMPTS + 1) must be locked");
    }

    @Test
    void successfulLoginClearsThePriorFailureCount() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i < LoginThrottle.MAX_ATTEMPTS; i++) {
            throttle.recordFailure("acme", "alice");
        }
        throttle.recordSuccess("acme", "alice");
        assertFalse(throttle.isLocked("acme", "alice"), "a successful login must reset the window");
    }

    @Test
    void lockIsScopedPerTenantAndUsername() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i <= LoginThrottle.MAX_ATTEMPTS; i++) {
            throttle.recordFailure("acme", "alice");
        }
        assertTrue(throttle.isLocked("acme", "alice"));
        assertFalse(throttle.isLocked("acme", "bob"), "a different username must not be locked");
        assertFalse(throttle.isLocked("beta", "alice"), "the same username in a different tenant must not be locked");
    }

    @Test
    void lockExpiresAfterTheWindowElapses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i <= LoginThrottle.MAX_ATTEMPTS; i++) {
            throttle.recordFailure("acme", "alice");
        }
        assertTrue(throttle.isLocked("acme", "alice"));

        clock.advance(Duration.ofMinutes(16));
        assertFalse(throttle.isLocked("acme", "alice"), "the window must have expired after 16 minutes");
    }

    @Test
    void retryAfterSecondsReflectsRemainingWindowTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock);
        throttle.recordFailure("acme", "alice");

        clock.advance(Duration.ofMinutes(5));
        long retryAfter = throttle.retryAfterSeconds("acme", "alice");
        assertEquals(600, retryAfter, "10 of the 15-minute window remain");
    }

    /** A {@link Clock} whose "now" can be advanced deterministically, for window-boundary tests. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
