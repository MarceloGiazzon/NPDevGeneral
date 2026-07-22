package com.npdev.adapters.bulkhead.postgres;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresAdvisoryBulkheadStoreTest {

    @Test
    void releaseAllowsAnotherAcquireForSameKey() {
        PostgresAdvisoryBulkheadStore store = new PostgresAdvisoryBulkheadStore(PostgresTestSupport.dataSource());
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "payments", "submit");

        assertTrue(store.tryAcquire(key, 1, 1000L));
        assertFalse(store.tryAcquire(key, 1, 1001L));
        store.release(key);
        assertTrue(store.tryAcquire(key, 1, 1002L));
        store.release(key);
    }

    @Test
    void onlyOneThreadCanAcquireSameAdvisoryLock() throws Exception {
        PostgresAdvisoryBulkheadStore store = new PostgresAdvisoryBulkheadStore(PostgresTestSupport.dataSource());
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "payments", "submit");
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch attemptsFinished = new CountDownLatch(2);
        CountDownLatch releaseGate = new CountDownLatch(1);
        AtomicInteger acquiredCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?>[] futures = new Future<?>[2];
            for (int index = 0; index < 2; index++) {
                futures[index] = executor.submit(() -> {
                    boolean acquired = false;
                    try {
                        assertTrue(startGate.await(5, TimeUnit.SECONDS));
                        acquired = store.tryAcquire(key, 1, System.currentTimeMillis());
                        if (acquired) {
                            acquiredCount.incrementAndGet();
                        }
                        attemptsFinished.countDown();
                        if (acquired) {
                            assertTrue(releaseGate.await(5, TimeUnit.SECONDS));
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        if (acquired) {
                            store.release(key);
                        }
                    }
                });
            }

            startGate.countDown();
            assertTrue(attemptsFinished.await(10, TimeUnit.SECONDS));
            assertEquals(1, acquiredCount.get());
            releaseGate.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            releaseGate.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
