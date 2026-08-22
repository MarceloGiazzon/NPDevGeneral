package com.npdev.kernel.i18n;

import com.npdev.kernel.ExecutionContext;

import java.util.Locale;
import java.util.Map;

/**
 * R5.6: resolves one label site (a resolved default text plus zero or more per-locale overrides,
 * exactly the shape {@code CompiledPresentationMetadata}/{@code CompiledProperty}/etc. carry as
 * {@code label()} + {@code labelLocales()} after the DSL four-place chain) against a requested
 * locale.
 *
 * <p><b>Fallback rule, deterministic and never blank (done-when requirement):</b>
 * <ol>
 *   <li>Exact locale tag match (case-insensitive) -- {@code "pt-BR"} against a declared
 *       {@code "pt-BR"} entry.</li>
 *   <li>Language-only match (case-insensitive) -- {@code "pt-BR"} against a declared {@code "pt"}
 *       entry, or {@code "pt"} against a declared {@code "pt-BR"} entry when no more specific
 *       region variant is authored. The FIRST declared entry (map iteration order, which for
 *       every compiled label is the sorted order the canonical-JSON writer emits) whose language
 *       matches wins -- deterministic, not "whichever the map happens to iterate to first" on an
 *       unspecified-order map.</li>
 *   <li>{@code defaultText} -- the schema-required terminal fallback (a label site's plain-string
 *       form IS this value; the object form's "default" key IS this value). Never null/blank for
 *       any label site that was actually authored, so this method only returns blank when the
 *       label site itself was never authored (defaultText null/blank AND no locale entries) --
 *       there is no "random map entry" case: an unmatched requested locale with no default falls
 *       through to whatever defaultText already was, exactly like every pre-R5.6 caller of a
 *       label getter already tolerated.</li>
 * </ol>
 *
 * <p>Stateless and pure -- no I/O, no caching, safe to call per-request per-label. Locale tags are
 * treated as opaque BCP-47-ish strings (matching this codebase's existing "informational tag, not
 * a closed set" treatment of locale elsewhere, e.g. {@code CompiledSettings.locale}); this class
 * does not validate them against any registry.
 */
public final class LabelResolver {

    private LabelResolver() {
    }

    /**
     * Resolves a label site's text for {@code requestedLocale}. {@code requestedLocale} may be
     * null/blank (no locale requested -- returns {@code defaultText} immediately) or absent from
     * {@code locales} entirely (falls through the language-only step to {@code defaultText}).
     */
    public static String resolve(String defaultText, Map<String, String> locales, String requestedLocale) {
        if (requestedLocale == null || requestedLocale.isBlank() || locales == null || locales.isEmpty()) {
            return defaultText;
        }
        String exact = lookupCaseInsensitive(locales, requestedLocale);
        if (exact != null) {
            return exact;
        }
        String requestedLanguage = languageOf(requestedLocale);
        if (requestedLanguage != null) {
            for (Map.Entry<String, String> entry : locales.entrySet()) {
                if (requestedLanguage.equalsIgnoreCase(languageOf(entry.getKey()))) {
                    return entry.getValue();
                }
            }
        }
        return defaultText;
    }

    /**
     * Convenience overload reading the requested locale off {@link ExecutionContext#locale()} --
     * the one seam that exists server-side today (R5.6 STEP 0 finding: no other request/session
     * object carries a user locale anywhere in the kernel/adapter/runtimehost chain). Returns
     * {@code defaultText} unchanged when the context carries no locale, exactly like {@link
     * #resolve} does for a null {@code requestedLocale}.
     */
    public static String resolve(String defaultText, Map<String, String> locales, ExecutionContext context) {
        return resolve(defaultText, locales, context == null ? null : context.locale());
    }

    private static String lookupCaseInsensitive(Map<String, String> locales, String requestedLocale) {
        String direct = locales.get(requestedLocale);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : locales.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(requestedLocale)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String languageOf(String localeTag) {
        if (localeTag == null) {
            return null;
        }
        String trimmed = localeTag.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int dash = trimmed.indexOf('-');
        String language = dash > 0 ? trimmed.substring(0, dash) : trimmed;
        return language.toLowerCase(Locale.ROOT);
    }
}
