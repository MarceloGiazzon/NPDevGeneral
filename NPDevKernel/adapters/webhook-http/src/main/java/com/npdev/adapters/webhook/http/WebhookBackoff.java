package com.npdev.adapters.webhook.http;

import java.time.Duration;

/**
 * INT-4: the outbox retry schedule -- a pure function of how many attempts have already been made.
 * Attempts 1..5 wait 30 s, 2 min, 10 min, 1 h, 6 h; after {@link #MAX_ATTEMPTS} failed attempts the
 * delivery is dead-lettered (FAILED) and only an explicit redeliver brings it back.
 */
public final class WebhookBackoff {

    public static final int MAX_ATTEMPTS = 6;

    private static final Duration[] DELAYS = {
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(6)
    };

    private WebhookBackoff() {
    }

    /** Delay before the next attempt once {@code attemptsSoFar} attempts have failed (clamped to the table). */
    public static Duration delayAfter(int attemptsSoFar) {
        int index = Math.max(1, Math.min(attemptsSoFar, DELAYS.length)) - 1;
        return DELAYS[index];
    }

    /** True while another attempt is allowed after {@code attemptsSoFar} failed ones. */
    public static boolean hasAttemptsLeft(int attemptsSoFar) {
        return attemptsSoFar < MAX_ATTEMPTS;
    }
}
