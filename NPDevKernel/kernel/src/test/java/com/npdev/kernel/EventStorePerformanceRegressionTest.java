package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// W3.3 (2026-08-25 remediation plan / QUAL-33): revived from @Disabled. The original stub's
// aspirational targets were a mix of two different things, split apart here:
//   - "10,000 events/second" and "p99 write latency < 10ms" are PERFORMANCE targets. Not tested --
//     a hard wall-clock assertion in a shared-CI-runner test measures machine load, not code (this
//     repo's own standing rule on cross-run perf comparisons).
//   - "crash recovery without data loss" does not apply to InMemoryEventStore at all: it has no
//     persistence to recover FROM. What DOES apply, and IS a real correctness property this store
//     must hold, is that concurrent writers never lose an append -- tested below.
//   - "compaction without impacting active queries" describes a capability EventStore's own
//     interface does not have (append/publish/findFirst/readByCorrelation/readByEventName only, no
//     compact() of any kind) -- not tested, because there is nothing to call.
class EventStorePerformanceRegressionTest {

    @Test
    void manyAppendedEventsAreAllRetrievableByCorrelationAndByName() {
        InMemoryEventStore store = new InMemoryEventStore();
        int total = 5_000;
        for (int i = 0; i < total; i++) {
            store.append(envelope("OrderCreated", "corr-" + (i % 50), i));
        }

        assertEquals(total, store.size());
        assertEquals(total / 50, store.readByCorrelation("corr-7").size());
        assertEquals(total, store.readByEventName("OrderCreated").size());
    }

    @Test
    void findFirstReturnsTheCorrectSingleMatchAmongManyCandidates() {
        InMemoryEventStore store = new InMemoryEventStore();
        for (int i = 0; i < 1_000; i++) {
            store.append(envelope("OrderCreated", "corr-" + i, i));
            store.append(envelope("OrderShipped", "corr-" + i, i));
        }

        Optional<EventEnvelope> found = store.findFirst("OrderShipped", "corr-742");

        assertTrue(found.isPresent());
        assertEquals("corr-742", found.get().correlationId());
        assertEquals("OrderShipped", found.get().eventName());
    }

    // The real substance behind the original "crash recovery without data loss" target, made
    // testable for an in-memory store: concurrent writers must never lose an append. Deterministic
    // (counts, not timing) -- a lost write fails the count assertion regardless of scheduling.
    @Test
    void concurrentAppendsFromMultipleThreadsLoseNoEvents() throws InterruptedException {
        InMemoryEventStore store = new InMemoryEventStore();
        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger threadIndex = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    int idx = threadIndex.getAndIncrement();
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        store.append(envelope("ConcurrentWrite", "corr-" + idx, i));
                    }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "writer threads did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, store.size(),
                "every concurrent append must be retained -- a mismatch means a write was lost");
        assertEquals(threads * perThread, store.readByEventName("ConcurrentWrite").size());
    }

    private static EventEnvelope envelope(String eventName, String correlationId, int sequence) {
        return EventEnvelope.create(
                eventName,
                java.util.Map.of("sequence", sequence),
                correlationId,
                "cause-" + correlationId + "-" + sequence,
                "test-flow",
                0
        );
    }
}
