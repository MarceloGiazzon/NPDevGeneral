package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.ports.DocumentRenderContract;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * REG-12 Slice 3 (D-A1): the default, pure-JVM {@link DocumentRenderContract} adapter --
 * OpenHTMLtoPDF/PDFBox, no native or display dependencies, headless-safe on Linux CI (proven by a
 * throwaway spike ahead of this class; see `docs/REG12_DOCUMENT_EXPORT_PLAN.md` P0). Accepts the
 * CSS-subset limitation this honestly documents (no flexbox/grid layout, no JS) in exchange for
 * running anywhere the JVM does.
 */
public final class DocumentRenderInProcAdapter implements DocumentRenderContract {

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
