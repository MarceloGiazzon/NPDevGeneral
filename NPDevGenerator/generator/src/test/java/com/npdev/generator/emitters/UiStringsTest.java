package com.npdev.generator.emitters;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REG-349: the platform text catalogue ships English and Portuguese for the same ids. */
public class UiStringsTest {

    @Test
    void englishIsTheDefaultAndPortugueseIsShipped() {
        assertEquals("en", UiStrings.locales().get(0));
        assertTrue(UiStrings.locales().contains("pt"));
    }

    @Test
    void everyLanguageCarriesTheSameIds() {
        assertEquals(UiStrings.catalogue("en").keySet(), UiStrings.catalogue("pt").keySet());
        assertFalse(UiStrings.catalogue("en").isEmpty());
    }

    @Test
    void portugueseLayersOverEnglishWithTheContractChrome() {
        Map<String, Map<String, String>> byLocale = UiStrings.byLocale(null);
        assertEquals("Sign out", byLocale.get("en").get("shell.signOut"));
        assertEquals("Sair", byLocale.get("pt").get("shell.signOut"));
        assertEquals("Salvar", byLocale.get("pt").get("action.save"));
        assertNotEquals(byLocale.get("en").get("workbench.header"), byLocale.get("pt").get("workbench.header"));
    }

    @Test
    void anAppWithNoLocaleDefaultsToEnglish() {
        assertEquals("en", UiStrings.appLanguage(null));
    }
}
