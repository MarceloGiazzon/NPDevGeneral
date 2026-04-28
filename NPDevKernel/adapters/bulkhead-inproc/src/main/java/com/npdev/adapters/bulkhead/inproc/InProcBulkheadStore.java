package com.npdev.adapters.bulkhead.inproc;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.ports.BulkheadStore;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public final class InProcBulkheadStore implements BulkheadStore {
    private final Map<CapabilityOpKey, PermitBucket> permitsByKey = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(CapabilityOpKey key, int maxConcurrent, long nowMs) {
        Objects.requireNonNull(key, "key");
        int safeMax = maxConcurrent <= 0 ? 1 : maxConcurrent;
        PermitBucket bucket = permitsByKey.compute(
                key,
                (ignored, existing) -> existing == null || existing.maxPermits() != safeMax
                        ? new PermitBucket(new Semaphore(safeMax, true), safeMax)
                        : existing
        );
        return bucket.semaphore().tryAcquire();
    }

    @Override
    public void release(CapabilityOpKey key) {
        if (key == null) {
            return;
        }
        PermitBucket bucket = permitsByKey.get(key);
        if (bucket == null) {
            return;
        }
        Semaphore semaphore = bucket.semaphore();
        if (semaphore.availablePermits() >= bucket.maxPermits()) {
            return;
        }
        semaphore.release();
    }

    private record PermitBucket(
            Semaphore semaphore,
            int maxPermits
    ) {
    }
}
