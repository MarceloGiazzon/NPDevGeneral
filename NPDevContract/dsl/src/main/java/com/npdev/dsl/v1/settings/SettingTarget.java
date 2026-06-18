package com.npdev.dsl.v1.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifies the thing a setting is being resolved for, and produces the ordered chain
 * of override selectors (most specific first) that the resolver walks.
 *
 * <p>Selector grammar mirrors the config {@code overrides} envelope keys:
 * {@code app}, {@code module:<Name>}, {@code concept:<Name>}, {@code field:<Concept>.<field>}.
 * The {@code app} selector corresponds to the config {@code defaults} block and is always the
 * least-specific entry in the chain (the platform default sits below it, implicitly).</p>
 */
public final class SettingTarget {

    /** The selector key for the application-wide {@code defaults} block. */
    public static final String APP_SELECTOR = "app";

    private final List<String> selectorChain;

    private SettingTarget(List<String> selectorChain) {
        this.selectorChain = List.copyOf(selectorChain);
    }

    /** The whole application (config {@code defaults} block). */
    public static SettingTarget app() {
        return new SettingTarget(List.of(APP_SELECTOR));
    }

    public static SettingTarget module(String moduleName) {
        require(moduleName, "moduleName");
        return new SettingTarget(List.of("module:" + moduleName, APP_SELECTOR));
    }

    public static SettingTarget concept(String conceptName) {
        require(conceptName, "conceptName");
        return new SettingTarget(List.of("concept:" + conceptName, APP_SELECTOR));
    }

    public static SettingTarget conceptInModule(String moduleName, String conceptName) {
        require(moduleName, "moduleName");
        require(conceptName, "conceptName");
        return new SettingTarget(List.of("concept:" + conceptName, "module:" + moduleName, APP_SELECTOR));
    }

    public static SettingTarget field(String conceptName, String fieldName) {
        require(conceptName, "conceptName");
        require(fieldName, "fieldName");
        List<String> chain = new ArrayList<>();
        chain.add("field:" + conceptName + "." + fieldName);
        chain.add("concept:" + conceptName);
        chain.add(APP_SELECTOR);
        return new SettingTarget(chain);
    }

    /** Selectors from most specific to least specific (excludes the implicit platform default). */
    public List<String> selectorChain() {
        return selectorChain;
    }

    /** A short label for the most specific selector, useful in diagnostics. */
    public String describe() {
        return selectorChain.isEmpty() ? APP_SELECTOR : selectorChain.get(0);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
