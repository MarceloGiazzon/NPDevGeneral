package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.ports.DocumentRenderContract;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * REG-12 Slice 3 (D-A1): the default, pure-JVM {@link DocumentRenderContract} adapter --
 * OpenHTMLtoPDF/PDFBox, no native or display dependencies, headless-safe on Linux CI (proven by a
 * throwaway spike ahead of this class; see `docs/archive/programme-history/REG12_DOCUMENT_EXPORT_PLAN.md` P0). Accepts the
 * CSS-subset limitation this honestly documents (no flexbox/grid layout, no JS) in exchange for
 * running anywhere the JVM does.
 */
public final class DocumentRenderInProcAdapter implements DocumentRenderContract {

    /**
     * REG-16-resid Round 6 (R6-F3): refuse to fetch anything while rendering, except inline
     * {@code data:} URIs.
     *
     * <p>OpenHTMLtoPDF resolves external resources by default — {@code <img src="http://…">}, remote
     * stylesheets, {@code @import}, remote fonts. Rendering happens <b>inside the server</b>, so any
     * such fetch is a server-side request: an SSRF reaching internal hosts and cloud metadata
     * endpoints, and with {@code file:} URIs a local-file read.</p>
     *
     * <p><b>Nothing exploitable reaches this today</b>, and that was verified rather than assumed:
     * {@code DocumentRenderController} is the only caller, it composes the HTML itself, and it
     * HTML-escapes every record value, so no record can contribute a tag. But this class is a public
     * adapter behind a general {@code render(html, options)} contract — the first feature that renders
     * author-supplied or templated HTML gets SSRF for free unless the policy lives here, at the point
     * where the fetch would happen, rather than in whatever calls it.</p>
     *
     * <p>Returning {@code null} makes the renderer skip the resource and carry on, so a document that
     * references something external still renders (without it) instead of failing outright.</p>
     */
    private static final com.openhtmltopdf.extend.FSUriResolver DENY_EXTERNAL_URIS = (baseUri, uri) -> {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        // data: is inline bytes -- no request leaves the process, so it stays allowed and keeps
        // embedded logos/images working for any future templated document.
        return uri.regionMatches(true, 0, "data:", 0, 5) ? uri : null;
    };

    @Override
    public byte[] render(String html, RenderOptions options) {
        if (html == null || html.isBlank()) {
            throw new DocumentRenderException("Cannot render an empty HTML document", null);
        }
        RenderOptions effectiveOptions = options == null ? RenderOptions.defaults() : options;
        String withPageRule = withPageCss(html, effectiveOptions);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useUriResolver(DENY_EXTERNAL_URIS);
            builder.withHtmlContent(withPageRule, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException | RuntimeException exception) {
            throw new DocumentRenderException("Failed to render HTML to PDF", exception);
        }
    }

    /**
     * Injects a {@code @page { size: ...; margin: ...; }} rule derived from {@link RenderOptions} --
     * OpenHTMLtoPDF reads this CSS at-rule for page size/margins, so page geometry stays a document
     * property (declared per {@code document} kind) rather than an adapter-hardcoded constant.
     * Inserted as the first rule of a trailing {@code <style>} block so it cannot be shadowed by the
     * document's own styles (later rules win under normal CSS cascade for same-specificity {@code
     * @page} declarations, and this is the only {@code @page} rule the platform itself ever emits).
     */
    private static String withPageCss(String html, RenderOptions options) {
        String sizeKeyword = options.pageSize() == PageSize.LETTER ? "letter" : "A4";
        String pageRule = "<style>@page { size: " + sizeKeyword + "; margin: "
                + options.marginMm() + "mm; }</style>";
        int headClose = html.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + pageRule + html.substring(headClose);
        }
        // No <head> to anchor on (a minimal/hand-built HTML fragment) -- prepend instead; still
        // valid since OpenHTMLtoPDF accepts a <style> element anywhere before </html>.
        return pageRule + html;
    }
}
