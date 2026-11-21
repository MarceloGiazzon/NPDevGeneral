package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REG-349: the text catalogue the standalone sign-in pages (login, reset password, change password, security,
 * invite user, agent link) carry inline. These pages have no generated-ui-manifest, so each one hands
 * {@code NPDevI18n.use({{{i18nJson}}})} the same {@code strings} / {@code stringsByLocale} /
 * {@code defaultLanguage} triple the manifest holds. The visitor's language comes from
 * {@code localStorage npdev.ui.locale}, else the app's default language.
 */
final class PageI18n {

    private PageI18n() {
    }

    /** The JSON object for {@code NPDevI18n.use(...)}, safe to place inside a {@code <script>} element. */
    static String inlineJson(CompiledModel model) {
        // LinkedHashMap, never Map.of: Map.of iterates in a per-JVM random order and would break deterministic generation.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strings", UiStrings.appStrings(model));
        payload.put("stringsByLocale", UiStrings.byLocale(model));
        payload.put("defaultLanguage", UiStrings.appLanguage(model));
        // "</" inside a string must not be able to close the script element.
        return UiStrings.json(payload).replace("</", "<\\/");
    }
}
