package com.npdev.adapters.circuit.inproc;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;
import com.npdev.kernel.capability.CircuitState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InProcCircuitBreakerStateStoreTest {
    @Test
    void getPutResetLifecycleIsDeterministic() {
        InProcCircuitBreakerStateStore store = new InProcCircuitBreakerStateStore();
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "persistence", "save");

        assertEquals(CircuitState.CLOSED, store.get(key).state());

        CircuitBreakerState open = new CircuitBreakerState(CircuitState.OPEN, 5, 1000L, 1000L, 30000L, 0);
        store.put(key, open);

        CircuitBreakerState loaded = store.get(key);
        assertEquals(CircuitState.OPEN, loaded.state());
        assertEquals(5, loaded.consecutiveFailures());

        List<CircuitBreakerStateSummary> summaries = store.listStates("tenant-a", "persistence", "save", 10, 0);
        assertEquals(1, summaries.size());
        assertEquals(CircuitState.OPEN, summaries.get(0).state());

        store.reset(key);
        assertEquals(CircuitState.CLOSED, store.get(key).state());
    }

    /**
     * REG-37. The whole point of a circuit breaker is to react to a BURST of failures, so the counter
     * has to be exact under concurrency -- which is the one condition the old get-then-put could not
     * meet. Every one of these 1,600 failures must be counted; losing even one means the breaker opens
     * later than its configured threshold says it will.
     *
     * <p>The threshold is set absurdly high on purpose so the circuit never opens: this test is about
     * whether the COUNT is exact, and an opened circuit would mask an undercount behind a state change.
     * Proven RED against the pre-fix get-then-put -- it lost roughly two thirds of the increments.</p>
     */
    @Test
    void concurrentFailuresAreCountedExactly() throws Exception {
        InProcCircuitBreakerStateStore store = new InProcCircuitBreakerStateStore();
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "persistence", "save");

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                running.add(pool.submit(() -> {
                    startTogether.await();
                    for (int i = 0; i < perThread; i++) {
                        store.recordFailure(key, 1_000L + i, Integer.MAX_VALUE, 30_000L);
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<?> task : running) {
                task.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, store.get(key).consecutiveFailures(),
                "every concurrent failure must be counted -- an undercount opens the breaker late");
        assertEquals(CircuitState.CLOSED, store.get(key).state(),
                "threshold was Integer.MAX_VALUE, so the circuit must not have opened");
    }

    @Test
    void aFailureDuringAHalfOpenTrialReopensImmediately() {
        // The half-open path moved into CircuitBreakerTransitions with the REG-37 fix; pin it here so
        // the move is provably behaviour-preserving rather than assumed to be.
        InProcCircuitBreakerStateStore store = new InProcCircuitBreakerStateStore();
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "persistence", "save");
        store.put(key, new CircuitBreakerState(CircuitState.HALF_OPEN, 3, 1_000L, 1_000L, 2_000L, 1));

        CircuitBreakerState next = store.recordFailure(key, 5_000L, Integer.MAX_VALUE, 30_000L);

        assertEquals(CircuitState.OPEN, next.state(), "a half-open trial that fails re-opens regardless of threshold");
        assertEquals(4, next.consecutiveFailures());
        assertEquals(35_000L, next.halfOpenAllowedAtMs());
    }
}
