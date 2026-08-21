package com.npdev.kernel;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("EventStore has no standalone in-memory adapter — only EventStoreStub inside KernelRunnerTest. "
        + "Performance benchmarks require embedded Postgres via Testcontainers.")
class EventStorePerformanceRegressionTest {

    @Test
    void eventStoreThroughputLatencyRecoveryAndCompactionAnchorsExist() {
        // Original aspirational targets (kept as documentation):
        // - 10,000 events/second throughput
        // - p99 write latency < 10ms, 10ms event-write budget
        // - Crash recovery without data loss
        // - Compaction / archived events without impacting active queries
        // Implement when: a standalone EventStore adapter on Testcontainers-backed Postgres exists.
    }
}