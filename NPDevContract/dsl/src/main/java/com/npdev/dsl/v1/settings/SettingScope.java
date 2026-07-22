package com.npdev.dsl.v1.settings;

/**
 * The levels at which an NPDev setting can be declared, ordered from least specific
 * (PLATFORM) to most specific (FIELD).
 *
 * <p>Resolution always walks from the most specific applicable level down to the
 * platform default, so a more specific declaration overrides a less specific one.
 * This is the mechanism behind the project rule: "a default always exists, and there
 * is always a way to override it."</p>
 */
public enum SettingScope {
    PLATFORM(0),
    APP(1),
    MODULE(2),
    CONCEPT(3),
    FIELD(4);

    private final int specificity;

    SettingScope(int specificity) {
        this.specificity = specificity;
    }

    /** Higher means more specific; a more specific scope overrides a less specific one. */
    public int specificity() {
        return specificity;
    }

    public boolean isMoreSpecificThan(SettingScope other) {
        return other != null && this.specificity > other.specificity;
    }
}
