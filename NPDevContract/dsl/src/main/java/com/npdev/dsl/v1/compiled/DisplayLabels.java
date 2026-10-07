package com.npdev.dsl.v1.compiled;

import java.util.Locale;

/**
 * The ONE default display label for an identifier with no declared label -- a field name, an enum
 * value. The generator's UI manifest ({@code BusinessUiEmitter}) and the live manifest
 * ({@code LiveConceptUiManifestSupport}) both call it, so a regenerated and a hot-reloaded app label
 * the same value the same way.
 *
 * <p>2026-10-07 (Pigmentampas): both copies used to put a space before EVERY capital, so the common
 * ALL-CAPS enum style rendered letter by letter ("G L O S S Y", "H E X_ O F F S E T").
 * <ul>
 *   <li>no lowercase letters (SCREAMING_SNAKE): words split on {@code _ - space}, sentence case --
 *       {@code GLOSSY -> Glossy}, {@code HEX_OFFSET -> Hex offset};</li>
 *   <li>otherwise (camelCase): a space at each lower/digit-to-upper boundary and before the last
 *       capital of an acronym run, {@code _}/{@code -} become spaces, first letter upper-cased --
 *       {@code dominantColor -> Dominant Color}, {@code URLPath -> URL Path}.</li>
 * </ul>
 */
public final class DisplayLabels {

    private DisplayLabels() {
    }

    public static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.chars().noneMatch(Character::isLowerCase)) {
            String words = String.join(" ", trimmed.toLowerCase(Locale.ROOT).split("[_\\-\\s]+")).trim();
            return capitalize(words);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '_' || c == '-') {
                out.append(' ');
                continue;
            }
            if (i > 0 && Character.isUpperCase(c)) {
                char previous = trimmed.charAt(i - 1);
                boolean afterLowerOrDigit = Character.isLowerCase(previous) || Character.isDigit(previous);
                boolean endsAcronym = Character.isUpperCase(previous)
                        && i + 1 < trimmed.length() && Character.isLowerCase(trimmed.charAt(i + 1));
                if (afterLowerOrDigit || endsAcronym) {
                    out.append(' ');
                }
            }
            out.append(c);
        }
        return capitalize(out.toString().replaceAll("\\s+", " ").trim());
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
