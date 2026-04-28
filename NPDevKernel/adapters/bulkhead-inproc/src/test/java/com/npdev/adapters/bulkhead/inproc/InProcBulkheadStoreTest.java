package com.npdev.adapters.bulkhead.inproc;

import com.npdev.kernel.capability.CapabilityOpKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcBulkheadStoreTest {
    @Test
    void enforcesMaxConcurrentPermits() {
        InProcBulkheadStore store = new InProcBulkheadStore();
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "persistence", "save");

        assertTrue(store.tryAcquire(key, 1, System.currentTimeMillis()));
        assertFalse(store.tryAcquire(key, 1, System.currentTimeMillis()));

        store.release(key);
        assertTrue(store.tryAcquire(key, 1, System.currentTimeMillis()));
    }
}
