package com.npdev.adapters.metrics.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrometerMetricsSinkTest {

    @Test
    void shouldRecordCounterTimerAndGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsSink sink = new MicrometerMetricsSink(registry);

        sink.inc("npdev.api.execute.started", Map.of("tenantId", "t1", "flowName", "CreateUser"));
        sink.observeMs("npdev.api.execute.duration_ms", 25L, Map.of("tenantId", "t1"));
        sink.gauge("npdev.scheduler.last_tick_ms", 123456L, Map.of("tenantId", "t1"));

        double count = registry.get("npdev.api.execute.started")
                .tags("tenantId", "t1", "flowName", "CreateUser")
                .counter()
                .count();
        assertEquals(1.0d, count);
        assertEquals(1L, registry.get("npdev.api.execute.duration_ms").timer().count());
        double gauge = registry.get("npdev.scheduler.last_tick_ms")
                .tags("tenantId", "t1")
                .gauge()
                .value();
        assertEquals(123456.0d, gauge);
    }
}
