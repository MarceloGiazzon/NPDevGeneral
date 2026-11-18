package com.finalexec.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FORM P5-T5: the in-process publish/subscribe hub behind {@code GET /api/live}, keyed by tenant.
 *
 * <ul>
 *   <li>{@link #publish} is called from the write path and must never fail or slow it: it only offers into bounded
 *       per-subscriber queues and hands delivery to a small daemon pool; everything is swallowed (logged at debug).</li>
 *   <li>A subscriber sees only events of ITS tenant and only the concepts it asked for (case-insensitive).</li>
 *   <li>Each queue is bounded: a burst on one row coalesces into the newest {@code (concept, id)} entry, and past the
 *       bound the OLDEST entry is dropped -- a slow reader loses intermediate notices, never blocks a writer. Losing
 *       one is harmless because the client's reaction to any notice is a full refetch.</li>
 *   <li>At most {@link #maxStreamsPerUser()} concurrent subscriptions per (tenant, user); one more is refused with
 *       {@link TooManyStreamsException}.</li>
 *   <li>A heartbeat is offered to every subscriber every {@code heartbeatMillis}, so a dead peer is found by the write
 *       failing, and proxies do not time an idle stream out.</li>
 * </ul>
 *
 * Single JVM only: with several app instances each hub sees only the writes made on its own instance.
 */
public final class LiveChangeHub {

    public static final int DEFAULT_MAX_STREAMS_PER_USER = 5;
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final long DEFAULT_HEARTBEAT_MILLIS = 20_000L;
    private static final int DELIVERY_THREADS = 4;
    private static final Logger LOG = LoggerFactory.getLogger(LiveChangeHub.class);

    /** Where a subscriber's events go. Called from a hub delivery thread, never from a writer's thread. An exception ends the subscription. */
    public interface Sink {
        void changed(LiveChange change) throws Exception;

        void heartbeat() throws Exception;
    }

    public static final class TooManyStreamsException extends RuntimeException {
        public TooManyStreamsException(String message) {
            super(message);
        }
    }

    private final int maxStreamsPerUser;
    private final int queueCapacity;
    private final Executor delivery;
    private final ExecutorService ownedDelivery;
    private final ScheduledExecutorService heartbeats;
    private final Map<String, Set<Subscription>> byTenant = new ConcurrentHashMap<>();
    private final Map<String, Integer> streamsByUser = new HashMap<>();
    private final AtomicLong published = new AtomicLong();

    public LiveChangeHub() {
        this(DEFAULT_MAX_STREAMS_PER_USER, DEFAULT_QUEUE_CAPACITY, DEFAULT_HEARTBEAT_MILLIS, null);
    }

    /** {@code deliveryExecutor} null = an owned pool of daemon threads; tests pass {@code Runnable::run} for determinism. {@code heartbeatMillis <= 0} = no heartbeat timer. */
    public LiveChangeHub(int maxStreamsPerUser, int queueCapacity, long heartbeatMillis, Executor deliveryExecutor) {
        this.maxStreamsPerUser = Math.max(1, maxStreamsPerUser);
        this.queueCapacity = Math.max(1, queueCapacity);
        if (deliveryExecutor == null) {
            this.ownedDelivery = Executors.newFixedThreadPool(DELIVERY_THREADS, daemon("npdev-live-delivery"));
            this.delivery = ownedDelivery;
        } else {
            this.ownedDelivery = null;
            this.delivery = deliveryExecutor;
        }
        if (heartbeatMillis > 0) {
            this.heartbeats = Executors.newSingleThreadScheduledExecutor(daemon("npdev-live-heartbeat"));
            this.heartbeats.scheduleWithFixedDelay(this::beat, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        } else {
            this.heartbeats = null;
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicLong n = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public int maxStreamsPerUser() {
        return maxStreamsPerUser;
    }

    /** Subscriptions currently open (all tenants). */
    public int subscriberCount() {
        int total = 0;
        for (Set<Subscription> subs : byTenant.values()) {
            total += subs.size();
        }
        return total;
    }

    public long publishedCount() {
        return published.get();
    }

    /**
     * @throws TooManyStreamsException when {@code user} already has the maximum number of open subscriptions in this tenant
     */
    public Subscription subscribe(String tenant, String user, Collection<String> concepts, Sink sink) {
        String tenantKey = tenantKey(tenant);
        String userKey = tenantKey + "\u0000" + (user == null ? "" : user);
        Set<String> wanted = new HashSet<>();
        if (concepts != null) {
            for (String concept : concepts) {
                if (concept != null && !concept.isBlank()) {
                    wanted.add(conceptKey(concept));
                }
            }
        }
        Subscription subscription = new Subscription(tenantKey, userKey, wanted, sink);
        synchronized (streamsByUser) {
            int open = streamsByUser.getOrDefault(userKey, 0);
            if (open >= maxStreamsPerUser) {
                throw new TooManyStreamsException("Too many live streams are open for this user (max " + maxStreamsPerUser + ").");
            }
            streamsByUser.put(userKey, open + 1);
        }
        byTenant.computeIfAbsent(tenantKey, ignored -> new CopyOnWriteArraySet<>()).add(subscription);
        return subscription;
    }

    /** Tell every matching subscriber that a row changed. Never throws, never blocks on a subscriber. */
    public void publish(String tenant, String concept, String id, Long version) {
        try {
            Set<Subscription> subs = byTenant.get(tenantKey(tenant));
            if (subs == null || subs.isEmpty() || concept == null || id == null) {
                return;
            }
            published.incrementAndGet();
            String conceptKey = conceptKey(concept);
            LiveChange change = new LiveChange(concept, id, version);
            for (Subscription sub : subs) {
                if (sub.concepts.contains(conceptKey)) {
                    sub.offer(change);
                }
            }
        } catch (Throwable problem) {
            LOG.debug("live publish ignored: {}", problem.toString());
        }
    }

    private void beat() {
        try {
            for (Set<Subscription> subs : byTenant.values()) {
                for (Subscription sub : subs) {
                    sub.offerHeartbeat();
                }
            }
        } catch (Throwable problem) {
            LOG.debug("live heartbeat ignored: {}", problem.toString());
        }
    }

    /** Stops the timers and closes every subscription (application shutdown). */
    public void shutdown() {
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
        List<Subscription> all = new ArrayList<>();
        byTenant.values().forEach(all::addAll);
        all.forEach(Subscription::close);
        if (ownedDelivery != null) {
            ownedDelivery.shutdownNow();
        }
    }

    private void release(Subscription subscription) {
        Set<Subscription> subs = byTenant.get(subscription.tenantKey);
        if (subs != null) {
            subs.remove(subscription);
            if (subs.isEmpty()) {
                byTenant.remove(subscription.tenantKey, subs);
            }
        }
        synchronized (streamsByUser) {
            int open = streamsByUser.getOrDefault(subscription.userKey, 0);
            if (open <= 1) {
                streamsByUser.remove(subscription.userKey);
            } else {
                streamsByUser.put(subscription.userKey, open - 1);
            }
        }
    }

    static String tenantKey(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim().toLowerCase(Locale.ROOT);
    }

    static String conceptKey(String concept) {
        return concept.trim().toLowerCase(Locale.ROOT);
    }

    /** One open stream. {@link #close()} is idempotent and safe from any thread. */
    public final class Subscription {
        private final String tenantKey;
        private final String userKey;
        private final Set<String> concepts;
        private final Sink sink;
        private final LinkedHashMap<String, LiveChange> pending = new LinkedHashMap<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong dropped = new AtomicLong();
        private boolean heartbeatDue;

        private Subscription(String tenantKey, String userKey, Set<String> concepts, Sink sink) {
            this.tenantKey = tenantKey;
            this.userKey = userKey;
            this.concepts = Set.copyOf(concepts);
            this.sink = sink;
        }

        public boolean isClosed() {
            return closed.get();
        }

        /** Entries discarded because the reader was too slow (bounded queue, oldest first). */
        public long droppedCount() {
            return dropped.get();
        }

        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (pending) {
                    pending.clear();
                }
                release(this);
            }
        }

        private void offer(LiveChange change) {
            if (closed.get()) {
                return;
            }
            synchronized (pending) {
                String key = change.concept().toLowerCase(Locale.ROOT) + "\u0000" + change.id();
                pending.remove(key); // a newer notice for the same row replaces the older one, and moves to the back
                pending.put(key, change);
                while (pending.size() > queueCapacity) {
                    Iterator<String> oldest = pending.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                    dropped.incrementAndGet();
                }
            }
            schedule();
        }

        private void offerHeartbeat() {
            if (closed.get()) {
                return;
            }
            synchronized (pending) {
                heartbeatDue = true;
            }
            schedule();
        }

        private void schedule() {
            if (scheduled.compareAndSet(false, true)) {
                try {
                    delivery.execute(this::drain);
                } catch (RuntimeException rejected) { // the pool is shutting down
                    scheduled.set(false);
                }
            }
        }

        private boolean hasWork() {
            synchronized (pending) {
                return !pending.isEmpty() || heartbeatDue;
            }
        }

        private void drain() {
            try {
                while (!closed.get()) {
                    LiveChange next = null;
                    boolean beat = false;
                    synchronized (pending) {
                        Iterator<LiveChange> it = pending.values().iterator();
                        if (it.hasNext()) {
                            next = it.next();
                            it.remove();
                        } else if (heartbeatDue) {
                            heartbeatDue = false;
                            beat = true;
                        }
                    }
                    if (next == null && !beat) {
                        break;
                    }
                    if (next != null) {
                        sink.changed(next);
                    } else {
                        sink.heartbeat();
                    }
                }
            } catch (Exception problem) { // the peer went away: end the subscription, the emitter's own callbacks do the rest
                LOG.debug("live subscriber closed after a failed write: {}", problem.toString());
                close();
            } finally {
                scheduled.set(false);
                if (!closed.get() && hasWork()) {
                    schedule();
                }
            }
        }
    }
}
