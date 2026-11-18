package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Move 6 Move A, FORM P5-T9g: the app's locale and string catalogue overrides, authored as the root
 * {@code backOffice.locale} / {@code backOffice.strings} (the old root {@code settings} key is gone, and so
 * are its never-read {@code ui.pageRows}/{@code ui.dateFormat}). {@code strings}
 * overrides a subset of the platform's closed string-id catalogue (see
 * {@code CompiledSettings}/{@code PlatformStrings}) -- ids not listed keep the platform's English
 * default. A model that declares neither keeps every platform default (see
 * {@link ModelAst#getSettings()}, which returns {@code null} in that case).
 */
public final class SettingsAst {
    private final String locale;
    private final Map<String, String> strings;

    public SettingsAst(String locale, Map<String, String> strings) {
        this.locale = locale;
        this.strings = strings == null ? Map.of() : new LinkedHashMap<>(strings);
    }

    public String getLocale() { return locale; }
    // REG-146: was Map.copyOf(strings) -- JDK's ImmutableCollections deliberately randomizes
    // iteration order per JVM run (a JEP 269 hash-flood mitigation), discarding the LinkedHashMap
    // insertion order the constructor above built, the same defect PlatformStrings.DEFAULTS had.
    // Collections.unmodifiableMap preserves real insertion order while staying just as immutable.
    public Map<String, String> getStrings() { return Collections.unmodifiableMap(strings); }
}
