package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.DocumentRenderContract;
import com.npdev.kernel.ports.DocumentRenderPayload;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REG-12 Slice 3 (D-A1): the default, pure-JVM {@link DocumentRenderContract} adapter --
 * OpenHTMLtoPDF/PDFBox, no native or display dependencies, headless-safe on Linux CI (proven by a
 * throwaway spike ahead of this class; see `docs/archive/programme-history/REG12_DOCUMENT_EXPORT_PLAN.md` P0). Accepts the
 * CSS-subset limitation this honestly documents (no flexbox/grid layout, no JS) in exchange for
 * running anywhere the JVM does.
 *
 * <p><b>R6.3 (RUN-18):</b> this class's original javadoc claimed rendering "lives behind a
 * port/adapter pair rather than a capability" -- true when written (REG-12 Slice 3 had exactly one
 * caller, the REST-only {@code DocumentRenderController}), but it also meant no flow step could ever
 * call it, which is precisely the gap R6.3 closes. It now ALSO implements {@link CapabilityAdapter}
 * (capability {@code documentRender}, operation {@code render}) so a flow's capabilityCall step can
 * dispatch to it exactly like {@code mail}/{@code webhook} already do, while {@link
 * DocumentRenderContract#render} itself -- the typed direct-call path {@code DocumentRenderController}
 * uses -- is unchanged.</p>
 *
 * <p><b>R5.7 (Roadmap Wave 1 2026-08-19):</b> a second capability operation, {@code renderAggregate},
 * renders the canonical ERP document shape -- a header plus one or more line-item bands, bound to an
 * {@code aggregate}'s already-loaded data tree, plus an optional logo. It composes HTML via {@link
 * AggregateDocumentHtmlBuilder} from a payload parsed by {@link AggregateDocumentPayload}, then calls
 * the SAME {@link #render(String, RenderOptions)} this class already exposes -- so it inherits {@link
 * #DENY_EXTERNAL_URIS} for free: a logo (or any other value) that is not an inline {@code data:} URI
 * is refused by the HTML builder before rendering even starts, not merely dropped by the resolver.
 * Neither {@link DocumentRenderContract} nor {@code CapabilityAdapter} (both in {@code
 * NPDevKernel/kernel/**}) needed to change for this -- {@code renderAggregate} is simply an
 * additional operation this adapter recognizes, the same way {@code render} already is one operation
 * among others a {@code CapabilityAdapter} can implement.</p>
 */
public final class DocumentRenderInProcAdapter implements CapabilityAdapter, DocumentRenderContract {

    @Override
    public String adapterId() {
        return "document-render-inproc";
    }

    @Override
    public String capability() {
        return "documentRender";
    }

    @Override
    public String capabilityType() {
        return "DocumentRenderCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if ("renderAggregate".equals(call.operation())) {
            return invokeRenderAggregate(call);
        }
        if (!"render".equals(call.operation())) {
            return CapabilityResult.failure(
                    "DOCUMENT_RENDER_OPERATION_UNSUPPORTED",
                    "Unsupported documentRender operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        }
        DocumentRenderPayload.RenderRequest request = DocumentRenderPayload.parse(call.args());
        try {
            byte[] pdfBytes = render(request.html(), request.toRenderOptions());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("contentBase64", Base64.getEncoder().encodeToString(pdfBytes));
            result.put("contentType", "application/pdf");
            result.put("filename", request.filenameOrDefault());
            result.put("sizeBytes", pdfBytes.length);
            return CapabilityResult.success(result);
        } catch (DocumentRenderException e) {
            return CapabilityResult.failure(
                    "DOCUMENT_RENDER_FAILED",
                    e.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of()
            );
        }
    }

    /**
     * R5.7: the {@code renderAggregate} operation -- see this class's javadoc. Failures (an
     * unresolvable band, an empty band, a non-{@code data:} logo, malformed HTML) come back as a
     * named {@code DOCUMENT_RENDER_FAILED} result exactly like the plain {@code render} operation's
     * failures do, never a silently blank PDF.
     */
    private CapabilityResult invokeRenderAggregate(CapabilityCall call) {
        try {
            AggregateDocumentPayload.Request request = AggregateDocumentPayload.parse(call.args());
            String html = AggregateDocumentHtmlBuilder.build(request);
            byte[] pdfBytes = render(html, request.toRenderOptions());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("contentBase64", Base64.getEncoder().encodeToString(pdfBytes));
            result.put("contentType", "application/pdf");
            result.put("filename", request.filenameOrDefault());
            result.put("sizeBytes", pdfBytes.length);
            return CapabilityResult.success(result);
        } catch (DocumentRenderException e) {
            return CapabilityResult.failure(
                    "DOCUMENT_RENDER_FAILED",
                    e.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of()
            );
        }
    }

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
