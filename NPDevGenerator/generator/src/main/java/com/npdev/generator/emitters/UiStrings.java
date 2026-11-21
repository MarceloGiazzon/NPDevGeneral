package com.npdev.generator.emitters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.PlatformStrings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REG-349: the catalogue of every platform-owned text the generated apps draw (business UI, shell,
 * workbench, sign-in pages), one catalogue per language. The ids and their English/Portuguese text live in
 * {@code npdev-i18n/<fragment>.<lang>.json} (fragments listed in {@code npdev-i18n/index.json}), one
 * fragment per area so that areas can be worked on side by side; {@code check-ui-i18n.py} keeps the
 * languages in step. The small contract-level catalogue ({@link PlatformStrings}: workbench.*, action.*,
 * shell.* chrome) stays where it is because the compiler bakes it into panel labels.
 *
 * <p>Resolution order for a language, lowest first: {@link PlatformStrings#DEFAULTS} (English) -&gt; this
 * catalogue in English -&gt; (for a non-English language) {@link PlatformStrings#localeDefaults} and this
 * catalogue in that language -&gt; the model's {@code backOffice.strings} (only for the app's own locale).
 * The browser applies the same chain in {@code views/i18n.js}.
 */
public final class UiStrings {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = "/npdev-i18n/";
    private static final Map<String, Map<String, String>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private UiStrings() {
    }

    /** The languages the platform ships a full catalogue for ("en" is the default and always first). */
    public static List<String> locales() {
        return readIndex().get("locales");
    }

    /** One language's catalogue from the fragment files (insertion order = index order, deterministic). */
    public static Map<String, String> catalogue(String language) {
        return CACHE.computeIfAbsent(language, UiStrings::load);
    }

    /** {@code language -> id -> text}, with the model's own overrides layered on the app's locale. */
    public static Map<String, Map<String, String>> byLocale(CompiledModel model) {
        String appLanguage = appLanguage(model);
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String language : locales()) {
            Map<String, String> merged = new LinkedHashMap<>(PlatformStrings.DEFAULTS);
            merged.putAll(catalogue("en"));
            if (!"en".equals(language)) {
                merged.putAll(PlatformStrings.localeDefaults(language));
                merged.putAll(catalogue(language));
            }
            if (language.equals(appLanguage) && model != null && model.getSettings() != null) {
                merged.putAll(model.getSettings().getStrings());
            }
            out.put(language, merged);
        }
        return out;
    }

    /** The catalogue for the app's own default locale (what a visitor with no language choice sees). */
    public static Map<String, String> appStrings(CompiledModel model) {
        Map<String, String> byApp = byLocale(model).get(appLanguage(model));
        return byApp != null ? byApp : byLocale(model).get("en");
    }

    /** The app's default language tag reduced to its language ("pt-BR" -&gt; "pt"); "en" when unset or unknown. */
    public static String appLanguage(CompiledModel model) {
        String locale = model != null && model.getSettings() != null ? model.getSettings().getLocale() : null;
        if (locale == null || locale.isBlank()) {
            return "en";
        }
        String language = locale.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return locales().contains(language) ? language : "en";
    }

    /**
     * The whole catalogue as the one JSON object a page hands to {@code NPDevI18n.use(...)}:
     * {@code {strings, stringsByLocale, defaultLanguage}}, safe to inline inside a {@code <script>} element
     * (every "&lt;/" is written "&lt;\/").
     */
    public static String catalogueJson(CompiledModel model) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("strings", appStrings(model));
        page.put("stringsByLocale", byLocale(model));
        page.put("defaultLanguage", appLanguage(model));
        return json(page).replace("</", "<\\/");
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> load(String language) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String fragment : readIndex().get("fragments")) {
            merged.putAll(readFlat(ROOT + fragment + "." + language + ".json"));
        }
        return Collections.unmodifiableMap(merged);
    }

    private static Map<String, List<String>> readIndex() {
        try (InputStream in = UiStrings.class.getResourceAsStream(ROOT + "index.json")) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + ROOT + "index.json");
            }
            return MAPPER.readValue(in, new TypeReference<Map<String, List<String>>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> readFlat(String resource) {
        try (InputStream in = UiStrings.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
