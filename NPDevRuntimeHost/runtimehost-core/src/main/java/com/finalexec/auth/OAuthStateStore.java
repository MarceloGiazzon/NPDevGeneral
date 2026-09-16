package com.finalexec.auth;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived store for the OAuth {@code state} parameter the platform minted for a pending
 * authorization-code flow (SEC-11, NPDEV_MEGA_ROADMAP.md 3b). The state binds a callback back to
 * the request that started it -- the CSRF protection of the authorize-redirect step: an
 * attacker cannot fabricate a valid state, so a callback carrying one can only be the tail of a
 * flow the app itself began. Entries expire on a TTL and are single-use (consumed on the
 * callback), so a captured state cannot be replayed into a second login.
 *
 * <p>The stored purpose distinguishes the unauthenticated signup/login legs (purpose
 * {@code login}) from the authenticated account-linking leg ({@code link}); the callback reads it
 * to decide which branch to take after the provider redirects back.
 *
 * <p>Process-local by design: the app is single-instance for the flows this supports (a
 * multi-instance deployment would need a shared store, which is outside this scope and documented
 * as such). Never logs a state value.
 */
public final class OAuthStateStore {

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();

    public OAuthStateStore(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public record Pending(String purpose, String next, long expiresAtMillis) {
    }

    /** Mint a fresh, unguessable state for the given purpose and remember it alongside the
     *  post-callback redirect target (already open-redirect-sanitized by the caller). */
    public String create(String purpose, String next) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String state = HexFormat.of().formatHex(raw);
        pending.put(state, new Pending(purpose, next == null ? "/" : next,
                System.currentTimeMillis() + ttlMillis));
        return state;
    }

    /**
     * Atomically consume a state: returns it only if it exists, is not expired, and is being
     * removed. Any other outcome (unknown, already used, too late) is {@code Optional.empty}
     * -- identical for every failure so a callback cannot probe which of the three it was.
     */
    public Optional<Pending> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        Pending removed = pending.remove(state);
        if (removed == null || removed.expiresAtMillis() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(removed);
    }
}