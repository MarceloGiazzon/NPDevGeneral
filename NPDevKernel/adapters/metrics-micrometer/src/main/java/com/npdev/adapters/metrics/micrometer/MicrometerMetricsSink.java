package com.npdev.adapters.metrics.micrometer;

import com.npdev.kernel.ports.MetricsSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MicrometerMetricsSink implements MetricsSink {
    private static final int MAX_TAGS = 8;
    private static final int MAX_TAG_KEY_LENGTH = 64;
    private static final int MAX_TAG_VALUE_LENGTH = 128;

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();

    public MicrometerMetricsSink(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public void inc(String name, Map<String, String> tags) {
        try {
            Counter.builder(normalizeMetricName(name))
                    .tags(flattenTags(sanitizeTags(tags)))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Metrics must never break execution.
        }
    }

    @Override
    public void observeMs(String name, long durationMs, Map<String, String> tags) {
        long safeDurationMs = Math.max(0L, durationMs);
        try {
            Timer.builder(normalizeMetricName(name))
                    .tags(flattenTags(sanitizeTags(tags)))
                    .register(meterRegistry)
                    .record(safeDurationMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // Metrics must never break execution.
        }
    }

    @Override
    public void gauge(String name, long value, Map<String, String> tags) {
        Map<String, String> safeTags = sanitizeTags(tags);
        String metricName = normalizeMetricName(name);
        String gaugeKey = gaugeKey(metricName, safeTags);
        AtomicLong holder = gaugeValues.computeIfAbsent(gaugeKey, ignored -> {
            AtomicLong atomicValue = new AtomicLong(0L);
            try {
                Gauge.builder(metricName, atomicValue, AtomicLong::get)
                        .tags(flattenTags(safeTags))
                        .register(meterRegistry);
            } catch (RuntimeException ignoredRegistrationFailure) {
                // Keep holder even if gauge registration fails to avoid repeated expensive retries.
            }
            return atomicValue;
        });
        holder.set(value);
    }

    private static String normalizeMetricName(String value) {
        String normalized = normalize(value, "unknown_metric", 200);
        return normalized.replace(' ', '_');
    }

    private static String gaugeKey(String metricName, Map<String, String> tags) {
        if (tags.isEmpty()) {
            return metricName;
        }
        StringBuilder builder = new StringBuilder(metricName).append('|');
        tags.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue())
                        .append(';'));
        return builder.toString();
    }

    private static Map<String, String> sanitizeTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        tags.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .limit(MAX_TAGS)
                .forEach(entry -> {
                    String key = normalize(entry.getKey(), null, MAX_TAG_KEY_LENGTH);
                    if (key == null) {
                        return;
                    }
                    String value = normalize(entry.getValue(), "<none>", MAX_TAG_VALUE_LENGTH);
                    safe.put(key, value);
                });
        return safe.isEmpty() ? Map.of() : Map.copyOf(safe);
    }

    private static Iterable<Tag> flattenTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<Tag> flattened = new ArrayList<>(tags.size());
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            flattened.add(Tag.of(entry.getKey(), entry.getValue()));
        }
        return flattened;
    }

    private static String normalize(String value, String fallback, int maxLength) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return fallback;
        }
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }
}
