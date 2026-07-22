package com.finalexec.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-20 (REG-16 finding F3): the per-IP arm of {@link LoginThrottle} must catch password-spraying
 * -- one password tried across many usernames from a single source -- which no per-username counter
 * ever sees.
 */
class LoginThrottleIpSprayTest {

    private static final Clock FROZEN = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    @Test
    void sprayingDistinctUsernamesFromOneIpTripsTheIpLock() {
        LoginThrottle throttle = new LoginThrottle(FROZEN);
        String ip = "203.0.113.7";

        // One failed attempt against each of IP_MAX_ATTEMPTS distinct usernames: no username ever
        // reaches its own threshold, but the IP does.
        for (int i = 0; i < LoginThrottle.IP_MAX_ATTEMPTS; i++) {
            String user = "victim-" + i;
            assertFalse(throttle.isLocked("t", user), "no single username should be locked by one attempt");
            throttle.recordFailure("t", user, ip);
        }

        // The NEXT username from that IP is now refused even though it has zero failures of its own.
        assertTrue(throttle.isLocked("t", "fresh-username", ip),
                "the per-IP ceiling must lock further attempts from a spraying source");
        // A different IP is unaffected.
        assertFalse(throttle.isLocked("t", "fresh-username", "198.51.100.1"),
                "a different source IP must not inherit the lock");
    }

    @Test
    void perUsernameLockStillWorksAndSuccessClearsItButNotTheIp() {
        LoginThrottle throttle = new LoginThrottle(FROZEN);
        String ip = "203.0.113.7";

        for (int i = 0; i < LoginThrottle.MAX_ATTEMPTS; i++) {
            throttle.recordFailure("t", "alice", ip);
        }
        assertTrue(throttle.isLocked("t", "alice", ip), "alice is locked by her own failures");

        throttle.recordSuccess("t", "alice");
        assertFalse(throttle.isLocked("t", "alice"), "a success clears the per-username window");

        // But the IP counter is NOT reset by a success, so an attacker holding one valid credential
        // cannot wipe the spray counter for the whole IP. (10 < IP ceiling, so not yet locked -- the
        // point is only that the IP window still carries alice's 10 failures.)
        assertFalse(throttle.isLocked("t", "bob", ip), "10 IP failures is below the IP ceiling");
        for (int i = 0; i < LoginThrottle.IP_MAX_ATTEMPTS - LoginThrottle.MAX_ATTEMPTS; i++) {
            throttle.recordFailure("t", "spray-" + i, ip);
        }
        assertTrue(throttle.isLocked("t", "bob", ip),
                "the success did not reset the IP counter, so the IP still reaches its ceiling");
    }
}
