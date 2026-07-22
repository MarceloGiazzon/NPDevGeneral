package com.finalexec.auth;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LNCH-4: brute-force throttling for {@link LoginController} -- fixed-window failed-attempt counter
 * per (tenant, username), independent of whether any individual attempt's password would have been
 * correct. In-memory by design (a single-instance deploy is this platform's honest v1 deployment
 * posture -- see LNCH-7/section 6 of the launch-readiness doc; a distributed limiter is a
 * horizontal-scaling concern, out of scope here). Resets on the JVM restarting, which is an
 * acceptable v1 trade-off: worst case an attacker gets a fresh window after a restart they don't
 * control.
 *
 * <p>Deliberately checked BEFORE the password comparison in {@link LoginController#login}, not
 * after: once locked, every further attempt in the window is rejected uniformly regardless of
 * whether it happens to be the right password, so a locked-out window cannot be used to keep probing.
 */
final class LoginThrottle {

    /** The 11th attempt in a window is refused -- ten wrong passwords are tolerated, matching the DoD. */
    static final int MAX_ATTEMPTS = 10;
    /**
     * REG-20 (REG-16 finding F3): default per-source-IP failed-attempt ceiling within a window. Set
     * deliberately higher than {@link #MAX_ATTEMPTS} because one IP can legitimately front many users
     * (NAT / office / carrier-grade NAT), but far below what a password-spray needs -- one password
     * tried across dozens of usernames from a single IP trips this even though no per-username counter
     * ever does. An attacker rotating IPs still raises their cost and footprint.
     */
    static final int IP_MAX_ATTEMPTS = 50;
    private static final long WINDOW_MILLIS = 15 * 60 * 1000L;

    /**
     * REG-19 (REG-16 finding F2): hard cap on the number of tracked (tenant,username) windows. An
     * unauthenticated attacker controls the key space, so without a cap a distinct-username spray
     * grows {@link #windowsByKey} until the JVM OOMs. When the map exceeds this, {@link #evictIfOverCap}
     * drops expired windows first and then the OLDEST live ones down to {@link #EVICTION_TARGET}. A
     * legitimate user who was JUST locked out has the newest window and therefore survives; the
     * windows sacrificed under extreme spray are the oldest, whose loss only means an attacker's stale
     * lockout is forgotten -- the same acceptable trade-off the JVM-restart case already documents.
     */
    static final int MAX_TRACKED_KEYS = 100_000;
    private static final int EVICTION_TARGET = 90_000;

    private final Clock clock;
    private final int maxAttempts;
    private final int ipMaxAttempts;
    private final Map<String, Window> windowsByKey = new ConcurrentHashMap<>();

    LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this(clock, MAX_ATTEMPTS, IP_MAX_ATTEMPTS);
    }

    /**
     * REG-20/REG-21: configurable thresholds so this same bounded sliding-window limiter backs both
     * login (per-username + per-IP) and the password-reset request endpoint (which wants a lower
     * ceiling). {@code ipMaxAttempts <= 0} disables the per-IP dimension.
     */
    LoginThrottle(Clock clock, int maxAttempts, int ipMaxAttempts) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.ipMaxAttempts = ipMaxAttempts;
    }

    /** REG-19: number of tracked (tenant,username) windows currently held in memory -- for tests to
     * assert the map stays bounded under a distinct-username spray. */
    int trackedKeyCount() {
        return windowsByKey.size();
    }

    /** {@code true} if this (tenant, username) has exceeded the per-username threshold in the window. */
    boolean isLocked(String tenantId, String username) {
        return isKeyLocked(userKey(tenantId, username), maxAttempts);
    }

    /**
     * REG-20: locked if EITHER the per-(tenant,username) counter OR the per-IP counter is over its
     * threshold. The per-IP arm is what stops password-spraying, which never trips a per-username
     * counter. A blank IP falls back to the username-only check.
     */
    boolean isLocked(String tenantId, String username, String clientIp) {
        if (isLocked(tenantId, username)) {
            return true;
        }
        String ip = normalizeIp(clientIp);
        return ip != null && ipMaxAttempts > 0 && isKeyLocked(ipKey(ip), ipMaxAttempts);
    }

    private boolean isKeyLocked(String key, int threshold) {
        Window window = windowsByKey.get(key);
        return window != null && !window.hasExpired(clock.millis()) && window.count >= threshold;
    }

    /** Records a failed login attempt, starting a fresh window if the previous one has expired. */
    void recordFailure(String tenantId, String username) {
        bump(userKey(tenantId, username));
    }

    /** REG-20: record a failed attempt against BOTH the per-username and the per-IP window. */
    void recordFailure(String tenantId, String username, String clientIp) {
        bump(userKey(tenantId, username));
        String ip = normalizeIp(clientIp);
        if (ip != null && ipMaxAttempts > 0) {
            bump(ipKey(ip));
        }
    }

    private void bump(String key) {
        windowsByKey.compute(key, (ignored, existing) -> {
            long now = clock.millis();
            if (existing == null || existing.hasExpired(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAtMillis, existing.count + 1);
        });
        // REG-19: bound the map. The size check is O(1) and usually returns immediately, so real
        // eviction work only runs once every ~(MAX-TARGET) inserts -- amortized-cheap, not per-call.
        if (windowsByKey.size() > MAX_TRACKED_KEYS) {
            evictIfOverCap(clock.millis());
        }
    }

    /**
     * REG-19: drop expired windows first (free -- they no longer gate anything), then, if still over
     * the cap, the OLDEST live windows down to {@link #EVICTION_TARGET}. Synchronized so a concurrent
     * spray runs one eviction pass rather than many racing ones; recordFailure's per-key
     * {@code compute} stays lock-free.
     */
    private synchronized void evictIfOverCap(long now) {
        if (windowsByKey.size() <= MAX_TRACKED_KEYS) {
            return; // another thread already evicted between the size check and here
        }
        windowsByKey.values().removeIf(window -> window.hasExpired(now));
        int size = windowsByKey.size();
        if (size <= EVICTION_TARGET) {
            return;
        }
        // Keep the newest EVICTION_TARGET windows: find the start-time cutoff and drop everything
        // strictly older. One pass to collect + sort, one pass to remove -- runs rarely.
        long[] starts = windowsByKey.values().stream().mapToLong(w -> w.startedAtMillis).sorted().toArray();
        long cutoff = starts[size - EVICTION_TARGET];
        windowsByKey.values().removeIf(window -> window.startedAtMillis < cutoff);
        // Break ties AT the cutoff timestamp so the cap is honoured even when many (or all -- e.g. a
        // burst within one millisecond, or a frozen test clock) windows share the same start time.
        int overflow = windowsByKey.size() - EVICTION_TARGET;
        if (overflow > 0) {
            var iterator = windowsByKey.values().iterator();
            while (overflow > 0 && iterator.hasNext()) {
                if (iterator.next().startedAtMillis == cutoff) {
                    iterator.remove();
                    overflow--;
                }
            }
        }
    }

    /**
     * A successful login clears the accumulated PER-USERNAME failures, so a legitimate user isn't
     * punished later. It deliberately does NOT clear the per-IP counter: an attacker who happens to
     * hold one valid credential must not be able to reset the spray counter for the whole IP by
     * interleaving a genuine login. The IP window simply expires on its own.
     */
    void recordSuccess(String tenantId, String username) {
        windowsByKey.remove(userKey(tenantId, username));
    }

    /** Seconds until the per-username window expires; for a Retry-After hint. */
    long retryAfterSeconds(String tenantId, String username) {
        return remainingSeconds(userKey(tenantId, username));
    }

    /** REG-20: retry-after across BOTH windows, whichever holds the caller longest. */
    long retryAfterSeconds(String tenantId, String username, String clientIp) {
        long user = remainingSeconds(userKey(tenantId, username));
        String ip = normalizeIp(clientIp);
        long viaIp = (ip == null || ipMaxAttempts <= 0) ? 0 : remainingSeconds(ipKey(ip));
        return Math.max(user, viaIp);
    }

    private long remainingSeconds(String key) {
        Window window = windowsByKey.get(key);
        if (window == null) {
            return 0;
        }
        long remainingMillis = (window.startedAtMillis + WINDOW_MILLIS) - clock.millis();
        return Math.max(0, remainingMillis / 1000L);
    }

    private static String ipKey(String clientIp) {
        return "i:" + clientIp;
    }

    private static String normalizeIp(String clientIp) {
        if (clientIp == null) {
            return null;
        }
        String trimmed = clientIp.trim();
        return trimmed.isBlank() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private static String userKey(String tenantId, String username) {
        String tenant = tenantId == null || tenantId.isBlank() ? "" : tenantId.trim().toLowerCase(java.util.Locale.ROOT);
        String user = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
        return "u:" + tenant.length() + ":" + tenant + ":" + user;
    }

    private static final class Window {
        private final long startedAtMillis;
        private final int count;

        private Window(long startedAtMillis, int count) {
            this.startedAtMillis = startedAtMillis;
            this.count = count;
        }

        private boolean hasExpired(long nowMillis) {
            return nowMillis - startedAtMillis > WINDOW_MILLIS;
        }
    }
}
