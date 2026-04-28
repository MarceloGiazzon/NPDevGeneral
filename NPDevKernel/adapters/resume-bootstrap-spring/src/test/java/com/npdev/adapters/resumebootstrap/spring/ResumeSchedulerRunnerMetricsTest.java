package com.npdev.adapters.resumebootstrap.spring;

import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.runtime.SchedulerRuntimeState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeSchedulerRunnerMetricsTest {

    @Test
    void pollShouldEmitSchedulerMetrics() {
        KernelRunner kernelRunner = new KernelRunner(event -> {
        }, (entity, payload) -> List.of());
        RecordingMetricsSink metricsSink = new RecordingMetricsSink();
        ResumeSchedulerRunner runner = new ResumeSchedulerRunner(
                kernelRunner,
                100,
                true,
                new SchedulerRuntimeState(),
                metricsSink
        );

        runner.poll();

        assertTrue(metricsSink.hasIncrement("npdev.scheduler.tick"), "Expected scheduler tick counter");
        assertTrue(metricsSink.hasGauge("npdev.scheduler.last_tick_ms"), "Expected scheduler last tick gauge");
    }

    private static final class RecordingMetricsSink implements MetricsSink {
        private final CopyOnWriteArrayList<String> increments = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> gauges = new CopyOnWriteArrayList<>();

        @Override
        public void inc(String name, Map<String, String> tags) {
            increments.add(name);
        }

        @Override
        public void observeMs(String name, long durationMs, Map<String, String> tags) {
            // no-op
        }

        @Override
        public void gauge(String name, long value, Map<String, String> tags) {
            gauges.add(name);
        }

        private boolean hasIncrement(String name) {
            return increments.stream().anyMatch(name::equals);
        }

        private boolean hasGauge(String name) {
            return gauges.stream().anyMatch(name::equals);
        }
    }
}