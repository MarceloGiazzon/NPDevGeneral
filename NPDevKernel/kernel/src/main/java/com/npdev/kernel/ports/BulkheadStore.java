package com.npdev.kernel.ports;

import com.npdev.kernel.capability.CapabilityOpKey;

public interface BulkheadStore {
    boolean tryAcquire(CapabilityOpKey key, int maxConcurrent, long nowMs);

    void release(CapabilityOpKey key);

    static BulkheadStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final BulkheadStore INSTANCE = new BulkheadStore() {
            @Override
            public boolean tryAcquire(CapabilityOpKey key, int maxConcurrent, long nowMs) {
                return true;
            }

            @Override
            public void release(CapabilityOpKey key) {
            }
        };

        private NoopHolder() {
        }
    }
}