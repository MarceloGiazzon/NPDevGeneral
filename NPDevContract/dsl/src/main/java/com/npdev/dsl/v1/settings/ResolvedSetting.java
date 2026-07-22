package com.npdev.dsl.v1.settings;

/**
 * The outcome of resolving one setting for one target: the effective value plus the
 * provenance that produced it. Provenance is what lets a box view show
 * "inherited" vs "overridden here".
 *
 * @param key            the setting that was resolved
 * @param value          the effective, coerced value
 * @param sourceScope    the scope that supplied the value ({@code PLATFORM} when the default won)
 * @param sourceSelector the selector that supplied the value
 *                       ({@link SettingResolver#PLATFORM_SELECTOR} for the platform default)
 * @param <T>            the value type
 */
public record ResolvedSetting<T>(SettingKey<T> key, T value, SettingScope sourceScope, String sourceSelector) {

    /** True when the value came from an explicit override rather than the platform default. */
    public boolean isOverridden() {
        return sourceScope != SettingScope.PLATFORM;
    }
}
