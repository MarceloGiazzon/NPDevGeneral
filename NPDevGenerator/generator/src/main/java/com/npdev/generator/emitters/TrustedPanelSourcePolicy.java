package com.npdev.generator.emitters;

import com.npdev.generator.emitters.trustedsource.model.PanelAssets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trusted-source panel HTML validation and sanitization: rejects unsafe DOM constructs (inline
 * event handlers, unsafe URLs, forbidden elements) and externalizes a panel's inline
 * {@code <style>}/{@code <script>} into the served {@link PanelAssets} triple.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2).
 */
final class TrustedPanelSourcePolicy {

    private static final Safelist PANEL_HTML_SAFELIST = Safelist.relaxed()
            .addTags("button", "main", "section", "article", "nav", "header", "footer", "template")
            .addAttributes(":all", "class", "id", "title", "role", "aria-label", "aria-describedby", "aria-controls", "aria-expanded", "aria-live")
            .addAttributes("button", "type", "name", "value")
            .addAttributes("input", "type", "name", "value", "placeholder", "checked", "disabled", "readonly")
            .addAttributes("label", "for")
            .addAttributes("form", "method")
            .preserveRelativeLinks(true);

    private TrustedPanelSourcePolicy() {
    }

    static void validatePanelSource(String source, String relativePath) {
        sanitizePanelAssets(source, relativePath, "validation");
    }

    static PanelAssets externalizePanelAssets(String source, String resourcePrefix) {
        return sanitizePanelAssets(source, resourcePrefix, resourcePrefix);
    }

    private static PanelAssets sanitizePanelAssets(String source, String relativePath, String resourcePrefix) {
        Document document = Jsoup.parse(source, "", Parser.htmlParser());
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        StringBuilder css = new StringBuilder();
        StringBuilder js = new StringBuilder();
        validatePanelDom(document, relativePath);
        for (Element style : document.select("style")) {
            css.append(style.data()).append("\n");
            style.remove();
        }
        for (Element script : document.select("script")) {
            if (script.hasAttr("src")) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": script src");
            }
            js.append(script.data()).append("\n");
            script.remove();
        }
        validatePanelCss(css.toString(), relativePath);
        validatePanelJavaScript(js.toString(), relativePath);

        Document cleaned = new Cleaner(PANEL_HTML_SAFELIST).clean(document);
        cleaned.outputSettings(new Document.OutputSettings().prettyPrint(false));
        if (!"validation".equals(resourcePrefix)) {
            cleaned.head().appendElement("link")
                    .attr("rel", "stylesheet")
                    .attr("href", "/generated/trusted-source/panel/" + resourcePrefix + ".css");
            cleaned.body().appendElement("script")
                    .attr("src", "/generated/trusted-source/panel/" + resourcePrefix + ".js");
        }
        String html = cleaned.outerHtml();
        if (Jsoup.parse(html).select("style,script:not([src])").size() > 0) {
            throw new IllegalStateException("Trusted panel sanitizer left inline style/script in generated HTML.");
        }
        return new PanelAssets(html, css.toString(), js.toString());
    }

    private static void validatePanelDom(Document document, String relativePath) {
        for (Element element : document.getAllElements()) {
            String tag = element.normalName();
            if (Set.of("iframe", "object", "embed", "base", "meta", "svg", "math").contains(tag)) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": element " + tag);
            }
            for (org.jsoup.nodes.Attribute attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue().trim();
                if (name.startsWith("on")) {
                    throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": inline event handler " + name);
                }
                if (Set.of("src", "href", "action", "formaction", "poster").contains(name) && isForbiddenPanelUrl(value)) {
                    throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": unsafe URL attribute " + name);
                }
                if ("style".equals(name)) {
                    validatePanelCss(value, relativePath);
                }
            }
        }
    }

    private static boolean isForbiddenPanelUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("\\p{Cntrl}", "").trim();
        return normalized.startsWith("http://")
                || normalized.startsWith("https://")
                || normalized.startsWith("//")
                || normalized.startsWith("javascript:")
                || normalized.startsWith("data:text/html")
                || normalized.startsWith("/.") 
                || (normalized.startsWith("/") && !normalized.startsWith("/generated/"));
    }

    private static void validatePanelCss(String css, String relativePath) {
        Map<String, String> forbidden = Map.ofEntries(
                Map.entry("css import", "(?i)@import\\s+"),
                Map.entry("css external url", "(?i)url\\s*\\(\\s*['\"]?\\s*(https?:)?//"),
                Map.entry("css javascript url", "(?i)url\\s*\\(\\s*['\"]?\\s*javascript:")
        );
        for (Map.Entry<String, String> pattern : forbidden.entrySet()) {
            if (Pattern.compile(pattern.getValue()).matcher(css).find()) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": " + pattern.getKey());
            }
        }
    }

    static void validatePanelJavaScript(String javascript, String relativePath) {
        Map<String, String> forbidden = Map.ofEntries(
                Map.entry("external fetch URL", "(?i)\\bfetch\\s*\\(\\s*['\"]\\s*(https?:)?//"),
                Map.entry("non-generated same-origin fetch", "(?i)\\bfetch\\s*\\(\\s*['\"]/(?!generated/)"),
                Map.entry("websocket URL", "(?i)\\bnew\\s+WebSocket\\s*\\(\\s*['\"]\\s*(wss?:)?//"),
                Map.entry("eval", "\\beval\\s*\\("),
                Map.entry("Function constructor", "\\bnew\\s+Function\\s*\\("),
                Map.entry("dynamic import", "\\bimport\\s*\\("),
                Map.entry("document cookie", "(?i)\\bdocument\\.cookie\\b"),
                Map.entry("local storage write", "(?i)\\blocalStorage\\.setItem\\s*\\(")
        );
        for (Map.Entry<String, String> pattern : forbidden.entrySet()) {
            if (Pattern.compile(pattern.getValue()).matcher(javascript).find()) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": " + pattern.getKey());
            }
        }
    }
}
