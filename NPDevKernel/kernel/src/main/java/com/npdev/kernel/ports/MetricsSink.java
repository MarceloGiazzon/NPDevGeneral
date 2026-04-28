package com.npdev.kernel.ports;

import java.util.Map;

public interface MetricsSink {
    void inc(String name, Map<String, String> tags);

    void observeMs(String name, long durationMs, Map<String, String> tags);

    default void gauge(String name, long value, Map<String, String> tags) {
    }

    static MetricsSink noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final MetricsSink INSTANCE = new MetricsSink() {
            @Override
            public void inc(String name, Map<String, String> tags) {
            }

            @Override
            public void observeMs(String name, long durationMs, Map<String, String> tags) {
            }
        };

        private NoopHolder() {
        }
    }
}
