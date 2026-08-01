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
    private final Integer pageRows;
    private final String dateFormat;

    public CompiledSettings(String locale, Map<String, String> strings, Integer pageRows, String dateFormat) {
        this.locale = locale;
        Map<String, String> merged = new LinkedHashMap<>(PlatformStrings.DEFAULTS);
        if (strings != null) {
            merged.putAll(strings);
        }
        this.strings = Collections.unmodifiableMap(merged);
        this.pageRows = pageRows;
        this.dateFormat = dateFormat;
    }

    /** The platform-defaults-only settings a model with no declared {@code settings} block gets. */
    public static CompiledSettings defaults() {
        return new CompiledSettings(null, Map.of(), null, null);
    }

    public String getLocale() { return locale; }
    public Map<String, String> getStrings() { return strings; }
    public String resolveString(String id) { return strings.get(id); }
    public Integer getPageRows() { return pageRows; }
    public String getDateFormat() { return dateFormat; }
}
