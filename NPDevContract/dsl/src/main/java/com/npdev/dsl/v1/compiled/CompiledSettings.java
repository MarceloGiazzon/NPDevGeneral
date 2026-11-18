package com.npdev.dsl.v1.compiled;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Move 6 Move A: app-level settings, compiled from {@code SettingsAst}. {@link #getStrings()} is
 * ALWAYS fully populated -- {@link PlatformStrings#DEFAULTS} merged with (overridden by) any
 * app-declared entries -- so every consumer (generator templates, {@code AutoPanelExpander}) can
 * resolve any catalogue id unconditionally, with no null/missing-key handling required.
 */
public final class CompiledSettings {
    private final String locale;
    private final Map<String, String> strings;

    public CompiledSettings(String locale, Map<String, String> strings) {
        this.locale = locale;
        Map<String, String> merged = new LinkedHashMap<>(PlatformStrings.DEFAULTS);
        merged.putAll(PlatformStrings.localeDefaults(locale));
        if (strings != null) {
            merged.putAll(strings);
        }
        this.strings = Collections.unmodifiableMap(merged);
    }

    /** The platform-defaults-only settings a model declaring no backOffice locale/strings gets. */
    public static CompiledSettings defaults() {
        return new CompiledSettings(null, Map.of());
    }

    public String getLocale() { return locale; }
    public Map<String, String> getStrings() { return strings; }
    public String resolveString(String id) { return strings.get(id); }
}
