package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.ports.DocumentRenderContract;

import java.util.List;
import java.util.Map;

/**
 * R5.7 (Roadmap Wave 1 2026-08-19): builds the header + line-item HTML for an aggregate-bound
 * document from its band definitions and the aggregate's already-loaded data tree ({@code
 * AggregateRuntime.load()}'s shape -- root fields at the top level of the map, a {@code
 * List<Map<String,Object>>} per declared collection). A band binds against that tree directly; this
 * class never re-queries anything.
 *
 * <p><b>Escaping duty:</b> every field value from the tree is HTML-escaped here. {@code
 * DocumentRenderController}'s existing flat-grid path escapes before ever calling the render
 * adapter; this structured path is new, and this class is the one place raw record values become
 * HTML markup, so the escaping has to live here or an aggregate field value could inject markup into
 * the rendered document.
 *
 * <p><b>The "blank page instead of an authoring error" rule (R5.7's watch item):</b> {@code
 * DocumentValidation} already rejects a band with zero fields at model-validate time, and rejects an
 * unknown {@code collection} name at the same time -- but a caller that hand-builds this adapter's
 * payload (bypassing the compiled model) gets no benefit from that. This builder re-checks both at
 * render time and throws {@link DocumentRenderContract.DocumentRenderException} rather than silently
 * emitting an empty table: a band naming a collection absent from the supplied tree is treated as a
 * wiring bug, not as "zero line items" (a genuinely empty {@code List} for a present collection key
 * IS legitimate and renders as a table with headers only -- an order with no lines yet is not an
 * error).
 */
final class AggregateDocumentHtmlBuilder {

    private AggregateDocumentHtmlBuilder() {
    }

    static String build(AggregateDocumentPayload.Request request) {
        if (request.bands().isEmpty()) {
            throw new DocumentRenderContract.DocumentRenderException(
                    "renderAggregate requires at least one band", null);
        }
        if (request.logoDataUri() != null
                && !request.logoDataUri().regionMatches(true, 0, "data:", 0, 5)) {
            // Defense in depth: render()'s own URI resolver (DENY_EXTERNAL_URIS) already refuses
            // anything but an inline data: URI, so a non-data: logo would just render without an
            // image -- but failing LOUDLY here instead makes a misconfigured caller's mistake (e.g.
            // accidentally passing a stored file's http(s) URL instead of its resolved bytes)
            // visible immediately rather than silently dropped.
            throw new DocumentRenderContract.DocumentRenderException(
                    "logoDataUri must be an inline data: URI, not a fetchable address -- resolve the "
                            + "logo field's bytes and embed them as data:<mime>;base64,... before calling render",
                    null);
        }

        StringBuilder html = new StringBuilder();
        html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<!DOCTYPE html>")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
                .append(escape(request.title()))
                .append("</title><style>")
                .append("body { font-family: sans-serif; font-size: 11px; }")
                .append(".doc-logo { max-height: 60px; margin-bottom: 8px; }")
                .append(".doc-header h1 { font-size: 16px; margin: 0 0 8px 0; }")
                .append(".doc-band-label { font-size: 13px; font-weight: bold; margin: 10px 0 4px 0; }")
                .append("table.doc-header-fields { border-collapse: collapse; margin-bottom: 12px; }")
                .append("table.doc-header-fields td { padding: 2px 8px 2px 0; }")
                .append("table.doc-header-fields td.label { font-weight: bold; }")
                .append("table.doc-band { border-collapse: collapse; width: 100%; margin-bottom: 12px; }")
                .append("table.doc-band th, table.doc-band td { border: 1px solid #000; padding: 4px; text-align: left; }")
                .append("</style></head><body>");

        if (request.logoDataUri() != null) {
            html.append("<img class=\"doc-logo\" src=\"").append(escape(request.logoDataUri())).append("\" />");
        }
        if (request.title() != null) {
            html.append("<div class=\"doc-header\"><h1>").append(escape(request.title())).append("</h1></div>");
        }

        for (AggregateDocumentPayload.Band band : request.bands()) {
            if (band.fields() == null || band.fields().isEmpty()) {
                throw new DocumentRenderContract.DocumentRenderException(
                        "band '" + band.name() + "' declares no fields -- refusing to render a blank "
                                + "header or a line-item table with no columns", null);
            }
            if ("lineItems".equalsIgnoreCase(band.kind())) {
                appendLineItemsBand(html, request.tree(), band);
            } else {
                appendHeaderBand(html, request.tree(), band);
            }
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static void appendHeaderBand(
            StringBuilder html, Map<String, Object> tree, AggregateDocumentPayload.Band band) {
        if (band.label() != null) {
            html.append("<div class=\"doc-band-label\">").append(escape(band.label())).append("</div>");
        }
        html.append("<table class=\"doc-header-fields\"><tbody>");
        for (AggregateDocumentPayload.BandField field : band.fields()) {
            Object value = tree == null ? null : tree.get(field.field());
            html.append("<tr><td class=\"label\">").append(escape(field.label())).append("</td><td>")
                    .append(escape(valueText(value))).append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private static void appendLineItemsBand(
            StringBuilder html, Map<String, Object> tree, AggregateDocumentPayload.Band band) {
        Object rowsValue = tree == null ? null : tree.get(band.collection());
        if (rowsValue == null) {
            // A band naming a collection the tree doesn't have at all is a wiring bug (wrong
            // collection name, or the tree wasn't loaded for this aggregate) -- not "zero line
            // items". See this class's javadoc for why that distinction matters.
            throw new DocumentRenderContract.DocumentRenderException(
                    "band '" + band.name() + "' is bound to collection '" + band.collection()
                            + "', which is absent from the supplied aggregate tree", null);
        }
        if (!(rowsValue instanceof List<?> rows)) {
            throw new DocumentRenderContract.DocumentRenderException(
                    "band '" + band.name() + "' collection '" + band.collection()
                            + "' is not a list in the supplied aggregate tree", null);
        }
        if (band.label() != null) {
            html.append("<div class=\"doc-band-label\">").append(escape(band.label())).append("</div>");
        }
        html.append("<table class=\"doc-band\"><thead><tr>");
        for (AggregateDocumentPayload.BandField field : band.fields()) {
            html.append("<th>").append(escape(field.label())).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (Object rowObj : rows) {
            html.append("<tr>");
            Map<?, ?> row = (rowObj instanceof Map<?, ?> asMap) ? asMap : Map.of();
            for (AggregateDocumentPayload.BandField field : band.fields()) {
                Object value = row.get(field.field());
                html.append("<td>").append(escape(valueText(value))).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    private static String valueText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
