package com.npdev.dsl.v1.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable collection of {@link SettingLayer}s indexed by selector, forming the override
 * sources for resolution. The platform default (held on each {@link SettingKey}) is the
 * implicit bottom layer and is never stored here.
 */
public final class SettingStore {

    private final Map<String, SettingLayer> layersBySelector;

    private SettingStore(Map<String, SettingLayer> layersBySelector) {
        this.layersBySelector = Map.copyOf(layersBySelector);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SettingStore empty() {
        return new Builder().build();
    }

    /** The layer registered for a selector, or {@code null} if none. */
    public SettingLayer layer(String selector) {
        return layersBySelector.get(selector);
    }

    public List<SettingLayer> layers() {
        return List.copyOf(layersBySelector.values());
    }

    public static final class Builder {
        private final Map<String, SettingLayer> layers = new LinkedHashMap<>();

        public Builder layer(SettingLayer layer) {
            if (layer != null) {
                layers.put(layer.selector(), layer);
            }
            return this;
        }

        public Builder layer(SettingScope scope, String selector, Map<String, Object> values, String sourceLabel) {
            return layer(new SettingLayer(scope, selector, values, sourceLabel));
        }

        public SettingStore build() {
            return new SettingStore(layers);
        }
    }
}
