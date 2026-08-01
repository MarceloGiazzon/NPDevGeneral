package com.npdev.dsl.v1.ast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Move 6 Move A: app-level settings (locale, string catalogue overrides, ui prefs). {@code strings}
 * overrides a subset of the platform's closed string-id catalogue (see
 * {@code CompiledSettings}/{@code PlatformStrings}) -- ids not listed keep the platform's English
 * default. A model that declares no {@code settings} block at all keeps every platform default (see
 * {@link ModelAst#getSettings()}, which returns {@code null} in that case).
 */
public final class SettingsAst {
    private final String locale;
    private final Map<String, String> strings;
    private final Integer pageRows;
    private final String dateFormat;

    public SettingsAst(String locale, Map<String, String> strings, Integer pageRows, String dateFormat) {
        this.locale = locale;
        this.strings = strings == null ? Map.of() : new LinkedHashMap<>(strings);
        this.pageRows = pageRows;
        this.dateFormat = dateFormat;
    }

    public String getLocale() { return locale; }
    public Map<String, String> getStrings() { return Map.copyOf(strings); }
    public Integer getPageRows() { return pageRows; }
    public String getDateFormat() { return dateFormat; }
}
