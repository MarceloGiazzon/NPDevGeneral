package com.npdev.kernel.ports;

import java.util.List;
import java.util.Map;

/**
 * R6.3 (RUN-18): parses the untyped payload a flow's {@code documentRender}/{@code render}
 * capabilityCall step passes into a {@link RenderRequest}. Shared by document-render-inproc and
 * document-render-stub so the payload shape is defined once, mirroring {@link MailPayload}'s role
 * for the mail capability.
 *
 * <p>Positional form (a flow's capabilityCall args are value refs, not an inline object literal --
 * see {@link MailPayload}'s own note on why): {@code (html[, pageSize, marginMm, filename])}. Map
 * form: {@code {"html":..., "pageSize":"A4"|"Letter", "marginMm":20.0, "filename":"report.pdf"}}.
 * Either shape may omit page options entirely, in which case {@link DocumentRenderContract
 * .RenderOptions#defaults()} applies -- unchanged from the pre-capability REST-only path.</p>
 */
public final class DocumentRenderPayload {

    private DocumentRenderPayload() {
    }

    public record RenderRequest(String html, DocumentRenderContract.PageSize pageSize, Double marginMm, String filename) {

        public DocumentRenderContract.RenderOptions toRenderOptions() {
            DocumentRenderContract.RenderOptions defaults = DocumentRenderContract.RenderOptions.defaults();
            return new DocumentRenderContract.RenderOptions(
                    pageSize == null ? defaults.pageSize() : pageSize,
                    marginMm == null ? defaults.marginMm() : marginMm
            );
        }

        public String filenameOrDefault() {
            return filename == null || filename.isBlank() ? "document.pdf" : filename;
        }
    }

    public static RenderRequest parse(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return new RenderRequest("", null, null, null);
        }
        if (args.size() == 1) {
            return parse(args.get(0));
        }
        String html = args.get(0) == null ? "" : String.valueOf(args.get(0));
        DocumentRenderContract.PageSize pageSize = args.size() > 1 ? toPageSize(args.get(1)) : null;
        Double marginMm = args.size() > 2 ? toDouble(args.get(2)) : null;
        String filename = args.size() > 3 && args.get(3) != null ? String.valueOf(args.get(3)) : null;
        return new RenderRequest(html, pageSize, marginMm, filename);
    }

    public static RenderRequest parse(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            String html = map.get("html") == null ? "" : String.valueOf(map.get("html"));
            DocumentRenderContract.PageSize pageSize = toPageSize(map.get("pageSize"));
            Double marginMm = toDouble(map.get("marginMm"));
            String filename = map.get("filename") == null ? null : String.valueOf(map.get("filename"));
            return new RenderRequest(html, pageSize, marginMm, filename);
        }
        return new RenderRequest(payload == null ? "" : String.valueOf(payload), null, null, null);
    }

    private static DocumentRenderContract.PageSize toPageSize(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return "LETTER".equalsIgnoreCase(text) ? DocumentRenderContract.PageSize.LETTER : DocumentRenderContract.PageSize.A4;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Double.valueOf(text);
    }
}
