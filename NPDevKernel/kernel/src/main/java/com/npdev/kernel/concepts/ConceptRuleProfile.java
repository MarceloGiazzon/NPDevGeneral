package com.npdev.kernel.concepts;

import java.util.Locale;
import java.util.Optional;

public enum ConceptRuleProfile {
    ALWAYS("always"),
    INTERACTIVE("interactive"),
    HEADLESS("headless"),
    QUERY("query"),
    BEFORE_COMMIT("beforeCommit"),
    AFTER_COMMIT("afterCommit");

    private final String canonicalName;

    ConceptRuleProfile(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public String canonicalName() {
        return canonicalName;
    }

    public static Optional<ConceptRuleProfile> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(name);
        for (ConceptRuleProfile profile : values()) {
            if (normalize(profile.canonicalName).equals(normalized)
                    || normalize(profile.name()).equals(normalized)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
}
