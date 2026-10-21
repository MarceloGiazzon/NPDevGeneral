package com.npdev.dsl.v1.compiled;

import java.util.Locale;

/**
 * The ONE English pluralisation rule the platform uses (REG-270). It serves both the REST
 * collection name of a concept ({@link SqlIdentifierSupport#restCollectionName}) and the plural
 * display label of a concept (generator {@code BusinessUiEmitter} and runtime-host
 * {@code LiveConceptUiManifestSupport}) -- those used to carry their own copies, one of which
 * simply appended {@code "s"} and served {@code TicketCategory} at {@code /ticket_categorys}.
 *
 * <p>Rules, applied to the last word and keeping the case of the stem:
 * <ul>
 *   <li>already ends in {@code s}: unchanged (idempotent -- {@code Orders} stays {@code Orders};
 *       this is also why {@code Status} and {@code Address} keep their name);</li>
 *   <li>consonant + {@code y}: {@code y} becomes {@code ies} ({@code category -> categories});</li>
 *   <li>vowel + {@code y}: plain {@code s} ({@code day -> days});</li>
 *   <li>{@code x}, {@code ch}, {@code sh}: {@code es} ({@code box -> boxes});</li>
 *   <li>anything else: {@code s}.</li>
 * </ul>
 * Irregular plurals ({@code person -> people}) are deliberately not guessed at.
 */
public final class EnglishPlurals {

    private EnglishPlurals() {
    }

    /** @return the plural of {@code word}; null/blank is returned unchanged. */
    public static String pluralize(String word) {
        if (word == null || word.isBlank()) {
            return word;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.endsWith("s")) {
            return word;
        }
        if (lower.endsWith("y") && lower.length() > 1 && !isVowel(lower.charAt(lower.length() - 2))) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh")) {
            return word + "es";
        }
        return word + "s";
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }
}
