package com.npdev.dsl.v1.settings;

/**
 * Resolves the effective value of a {@link SettingKey} for a {@link SettingTarget} by walking
 * the target's selector chain from most specific to least specific, then falling back to the
 * platform default. The first layer that declares the setting wins.
 *
 * <p>This is the single cascade used everywhere settings are read, so resolution is
 * deterministic and always carries provenance.</p>
 */
public final class SettingResolver {

    /** The {@link ResolvedSetting#sourceSelector()} reported when the platform default wins. */
    public static final String PLATFORM_SELECTOR = "platform-default";

    private final SettingStore store;

    public SettingResolver(SettingStore store) {
        this.store = store == null ? SettingStore.empty() : store;
    }

    public <T> ResolvedSetting<T> resolve(SettingKey<T> key, SettingTarget target) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        SettingTarget effectiveTarget = target == null ? SettingTarget.app() : target;
        for (String selector : effectiveTarget.selectorChain()) {
            SettingLayer layer = store.layer(selector);
            if (layer != null && layer.defines(key.id())) {
                T value = key.coerce(layer.raw(key.id()));
                return new ResolvedSetting<>(key, value, layer.scope(), layer.selector());
            }
        }
        return new ResolvedSetting<>(key, key.defaultValue(), SettingScope.PLATFORM, PLATFORM_SELECTOR);
    }

    /** Convenience: resolve and return just the effective value. */
    public <T> T value(SettingKey<T> key, SettingTarget target) {
        return resolve(key, target).value();
    }
}
