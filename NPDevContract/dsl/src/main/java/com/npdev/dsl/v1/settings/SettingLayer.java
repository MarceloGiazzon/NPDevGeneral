package com.npdev.dsl.v1.settings;

import java.util.Map;

/**
 * One source of override values, attached to a single selector (e.g. {@code "concept:Order"}).
 *
 * @param scope       the scope this layer sits at (drives provenance reporting)
 * @param selector    the selector key this layer answers to ({@code "app"}, {@code "concept:Order"}, ...)
 * @param values      settingId -&gt; raw value (coerced lazily at resolve time); must not contain nulls
 * @param sourceLabel human-readable origin, e.g. {@code "config.json overrides[concept:Order]"}
 */
public record SettingLayer(SettingScope scope, String selector, Map<String, Object> values, String sourceLabel) {

    public SettingLayer {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("selector must not be blank");
        }
        values = values == null ? Map.of() : Map.copyOf(values);
        sourceLabel = sourceLabel == null ? selector : sourceLabel;
    }

    public boolean defines(String settingId) {
        return values.containsKey(settingId);
    }

    public Object raw(String settingId) {
        return values.get(settingId);
    }
}
