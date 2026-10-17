package com.finalexec.auth.mfa;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SEC-13: the short-lived "password (or provider) OK, second factor pending" ticket between
 * POST /api/auth/login (or the OAuth callback) and POST /api/auth/login/mfa. Modeled on
 * OAuthStateStore: unguessable 32-byte hex id, TTL, process-local (single-instance assumption shared
 * with OAuthStateStore -- document it, do not solve it here).
 *
 * Differences from OAuthStateStore: an entry survives a WRONG code (so a typo does not force the
 * password again) but dies after MAX_ATTEMPTS wrong codes, on success, or at expiry. It is a static
 * shared instance because LoginController and OAuthGoogleController both create entries and their
 * constructors are pinned by direct-construction tests.
 */
public final class MfaChallengeStore {

    public static final long TTL_MILLIS = 5 * 60 * 1000L;
    public static final int MAX_ATTEMPTS = 5;
    private static final MfaChallengeStore SHARED = new MfaChallengeStore(Clock.systemUTC());

    public static MfaChallengeStore shared() {
        return SHARED;
    }

    /** next: already open-redirect-sanitized post-login target ("/" for a password login). */
    public record Challenge(String tenantId, String username, String next, long expiresAtMillis, int failures) {
    }

    private final Map<String, Challenge> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    MfaChallengeStore(Clock clock) {          // package-private: tests pass Clock.fixed / offset
        this.clock = clock;
    }

    public String create(String tenantId, String username, String next) {
        purgeExpired();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        entries.put(id, new Challenge(tenantId, username, next == null ? "/" : next,
                clock.millis() + TTL_MILLIS, 0));
        return id;
    }

    /** The live challenge, or empty (unknown / expired / exhausted) -- one shape for every failure. */
    public Optional<Challenge> peek(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        Challenge c = entries.get(id);
        if (c == null) return Optional.empty();
        if (c.expiresAtMillis() < clock.millis() || c.failures() >= MAX_ATTEMPTS) {
            entries.remove(id);
            return Optional.empty();
        }
        return Optional.of(c);
    }

    public void recordFailure(String id) {
        if (id == null) return;
        entries.computeIfPresent(id, (k, c) -> c.failures() + 1 >= MAX_ATTEMPTS ? null
                : new Challenge(c.tenantId(), c.username(), c.next(), c.expiresAtMillis(), c.failures() + 1));
    }

    /** Call on success: single use. */
    public void consume(String id) {
        if (id != null) entries.remove(id);
    }

    private void purgeExpired() {
        long now = clock.millis();
        entries.entrySet().removeIf(e -> e.getValue().expiresAtMillis() < now);
    }
}
