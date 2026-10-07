package com.finalexec.publicread;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * P6: per-client fixed-window limit for the anonymous {@code /api/public/**} surface -- the only
 * API an unauthenticated caller can reach in bulk, so it needs its own brake. {@code limitPerMinute
 * <= 0} disables it. The map is bounded: when it holds {@link #MAX_TRACKED_CLIENTS} clients, expired
 * windows are swept first and, if that frees nothing, the new client is refused (fails closed under
 * an address-spray instead of growing without bound).
 */
public final class PublicReadRateLimiter {

    public static final int MAX_TRACKED_CLIENTS = 10_000;
    private static final long WINDOW_MILLIS = 60_000L;

    private record Window(long startMillis, int count) {
    }

    private final int limitPerMinute;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PublicReadRateLimiter(int limitPerMinute) {
        this(limitPerMinute, System::currentTimeMillis);
    }

    PublicReadRateLimiter(int limitPerMinute, LongSupplier clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    /** @return 0 when allowed, otherwise the seconds until this client's window resets */
    public long tryAcquire(String clientKey) {
        if (limitPerMinute <= 0) {
            return 0;
        }
        String key = clientKey == null || clientKey.isBlank() ? "?" : clientKey;
        long now = clock.getAsLong();
        if (!windows.containsKey(key) && windows.size() >= MAX_TRACKED_CLIENTS) {
            sweep(now);
            if (windows.size() >= MAX_TRACKED_CLIENTS) {
                return WINDOW_MILLIS / 1000;
            }
        }
        Window window = windows.compute(key, (ignored, current) ->
                current == null || now - current.startMillis() >= WINDOW_MILLIS
                        ? new Window(now, 1)
                        : new Window(current.startMillis(), current.count() + 1));
        if (window.count() <= limitPerMinute) {
            return 0;
        }
        return Math.max(1, (window.startMillis() + WINDOW_MILLIS - now + 999) / 1000);
    }

    private void sweep(long now) {
        for (Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().startMillis() >= WINDOW_MILLIS) {
                it.remove();
            }
        }
    }
}
