package com.npdev.kernel.i18n;

import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R5.6: proves the deterministic fallback rule (exact locale -> language-only -> default) and
 * that a missing/unmatched locale never resolves to blank or a random map entry.
 */
class LabelResolverTest {

    @Test
    void plainStringLabelIgnoresRequestedLocale() {
        // The non-negotiable: a plain-string label (no locales map at all) resolves the same way
        // for every requested locale -- widening never changes existing single-locale behavior.
        assertEquals("Nome", LabelResolver.resolve("Nome", Map.of(), "en"));
        assertEquals("Nome", LabelResolver.resolve("Nome", Map.of(), "pt-BR"));
        assertEquals("Nome", LabelResolver.resolve("Nome", Map.of(), (String) null));
    }

    @Test
    void exactLocaleTagWins() {
        Map<String, String> locales = new LinkedHashMap<>();
        locales.put("pt-BR", "Nome");
        locales.put("en", "Name");
        assertEquals("Nome", LabelResolver.resolve("Name", locales, "pt-BR"));
        assertEquals("Name", LabelResolver.resolve("Name", locales, "en"));
    }

    @Test
    void exactMatchIsCaseInsensitive() {
        Map<String, String> locales = Map.of("pt-BR", "Nome");
        assertEquals("Nome", LabelResolver.resolve("Name", locales, "PT-br"));
    }

    @Test
    void languageOnlyMatchWhenNoExactRegionVariant() {
        // Requested "pt-PT" (Portugal) with only "pt-BR" declared -- no exact match, but same
        // language family, so the language-only step must still resolve it rather than falling
        // all the way through to the English default.
        Map<String, String> locales = Map.of("pt-BR", "Nome");
        assertEquals("Nome", LabelResolver.resolve("Name", locales, "pt-PT"));
    }

    @Test
    void bareLanguageRequestMatchesRegionVariant() {
        Map<String, String> locales = Map.of("pt-BR", "Nome");
        assertEquals("Nome", LabelResolver.resolve("Name", locales, "pt"));
    }

    @Test
    void unmatchedLocaleFallsBackToDefaultDeterministically() {
        Map<String, String> locales = new LinkedHashMap<>();
        locales.put("pt-BR", "Nome");
        locales.put("fr", "Nom");
        // "de" (German) matches neither entry exactly nor by language family -- must fall through
        // to the declared default, never to blank and never to whichever entry happens to be
        // first in iteration order.
        assertEquals("Name", LabelResolver.resolve("Name", locales, "de"));
    }

    @Test
    void nullOrBlankRequestedLocaleReturnsDefault() {
        Map<String, String> locales = Map.of("pt-BR", "Nome");
        assertEquals("Name", LabelResolver.resolve("Name", locales, (String) null));
        assertEquals("Name", LabelResolver.resolve("Name", locales, ""));
        assertEquals("Name", LabelResolver.resolve("Name", locales, "   "));
    }

    @Test
    void emptyLocalesMapAlwaysReturnsDefault() {
        assertNull(LabelResolver.resolve(null, Map.of(), "pt-BR"));
        assertEquals("Name", LabelResolver.resolve("Name", null, "pt-BR"));
    }

    @Test
    void resolvesFromExecutionContextLocaleTag() {
        Map<String, String> locales = Map.of("pt-BR", "Nome");
        ExecutionContext ptBr = ExecutionContext.anonymous().withTag("locale", "pt-BR");
        ExecutionContext noLocale = ExecutionContext.anonymous();

        assertEquals("Nome", LabelResolver.resolve("Name", locales, ptBr));
        assertEquals("Name", LabelResolver.resolve("Name", locales, noLocale));
        assertEquals("Name", LabelResolver.resolve("Name", locales, (ExecutionContext) null));
    }

    @Test
    void executionContextLocaleReadsTagsMap() {
        ExecutionContext context = ExecutionContext.anonymous().withTag("locale", "pt-BR");
        assertEquals("pt-BR", context.locale());
        assertNull(ExecutionContext.anonymous().locale());
    }
}
