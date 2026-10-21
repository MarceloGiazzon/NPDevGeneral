package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Locale;

/**
 * The domain type's {@code normalization} rules applied to one text value (REG-274): the catalog
 * served them to clients but the server never applied them, so {@code " ab-12 "} was stored as sent.
 *
 * <p>Vocabulary (case-insensitive, applied in declared order): {@code trim}, {@code uppercase},
 * {@code lowercase}, {@code collapseWhitespace}. A rule outside it is ignored, not an error: the
 * schema types the list as free-form strings. Pure; non-text values come back untouched.
 */
public final class DomainNormalization {

    private DomainNormalization() {
    }

    public static Object apply(List<String> rules, Object value) {
        if (!(value instanceof CharSequence) || rules == null || rules.isEmpty()) {
            return value;
        }
        String text = value.toString();
        for (String rule : rules) {
            if (rule == null) {
                continue;
            }
            switch (rule.trim().toLowerCase(Locale.ROOT)) {
                case "trim" -> text = text.strip();
                case "uppercase" -> text = text.toUpperCase(Locale.ROOT);
                case "lowercase" -> text = text.toLowerCase(Locale.ROOT);
                case "collapsewhitespace" -> text = text.replaceAll("\\s+", " ");
                default -> {
                    // not a rule the runtime knows; the catalog still serves it to clients
                }
            }
        }
        return text;
    }
}
