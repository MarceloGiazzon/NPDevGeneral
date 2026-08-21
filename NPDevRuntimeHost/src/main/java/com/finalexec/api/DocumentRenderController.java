package com.finalexec.api;

import com.finalexec.npdev.service.AggregateRuntime;
import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledDocumentBand;
import com.npdev.dsl.v1.compiled.CompiledDocumentLogo;
import com.npdev.dsl.v1.compiled.CompiledPanelFieldBinding;
import com.npdev.generated.runtime.model.NPDevModelProvider;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.DocumentRenderContract;
import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * LNCH-10 Slice 3 (REG-12): server-side rendered {@code document} objects -- a declared
 * {@code document} binds one concept's filtered/sorted query (the exact same path
 * {@link ConceptQueryController#exportCsv} uses, reusing {@link ConceptQueryController
 * #parseConceptQuery} so "what exports as CSV" and "what renders as PDF" are provably the same
 * query) to a server-rendered PDF via {@link DocumentRenderContract}. Static and document-generic
 * (like {@code export.csv} is concept-generic) -- resolves the declared {@code document} by name
 * from the compiled model at request time rather than being generated per-document, since no
 * per-document behavior differs beyond which concept/title/page options it declares.
 *
 * <p>Unlike CSV's incremental per-page streaming, the whole result set must be materialized before
 * rendering (the HTML-to-PDF adapter needs the complete document up front), so the TOTAL row count is
 * capped by {@link #MAX_DOCUMENT_ROWS} -- see R6-F1 below.
 */
@RestController
@RequestMapping({"/api/v1/documents", "/api/documents"})
public class DocumentRenderController {

    private static final int RENDER_PAGE_SIZE = ConceptQuery.MAX_LIMIT;

    /**
     * REG-16-resid Round 6 (R6-F1): a hard ceiling on how many rows one PDF may materialize.
     *
     * <p>This class's javadoc used to claim the accumulation loop was "bounded the same way CSV's
     * page loop is, at {@code ConceptQuery.MAX_LIMIT} rows per page while accumulating". That was
     * false in the way that matters: {@code MAX_LIMIT} bounds each PAGE, and the loop then appended
     * every page into one list. CSV can say that honestly because it <em>streams</em> — it writes and
     * flushes each page, then drops it. This path kept all of them, then built one HTML string from
     * them, then one PDF byte array. A single request against a large concept therefore held the
     * whole result set three times over, and on a shared host one tenant's export could exhaust
     * memory for every other tenant.</p>
     *
     * <p>The request is <b>rejected</b> past the ceiling rather than truncated. A report that
     * silently omits rows is a correctness failure that nobody notices; a 413 telling the caller to
     * narrow the filter is one they cannot miss.</p>
     */
    static final int MAX_DOCUMENT_ROWS = 50_000;

    private final NPDevModelProvider modelProvider;
    private final RuntimeContextService runtimeContextService;
    private final ConceptGateway conceptGateway;
    private final DocumentRenderContract documentRenderer;
    private final AggregateRuntime aggregateRuntime;
    private final FileStoreContract fileStore;

    public DocumentRenderController(
            NPDevModelProvider modelProvider,
            RuntimeContextService runtimeContextService,
            ConceptGateway conceptGateway,
            DocumentRenderContract documentRenderer,
            AggregateRuntime aggregateRuntime,
            FileStoreContract fileStore
    ) {
        this.modelProvider = modelProvider;
        this.runtimeContextService = runtimeContextService;
        this.conceptGateway = conceptGateway;
        this.documentRenderer = documentRenderer;
        this.aggregateRuntime = aggregateRuntime;
        this.fileStore = fileStore;
    }

    @GetMapping("/{document}/render.pdf")
    public void renderPdf(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable("document") String documentName
    ) throws IOException {
        if (conceptGateway == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "concept gateway not configured");
        }
        CompiledDocument document = findDocument(documentName);

        ConceptQuery baseQuery = ConceptQueryController.parseConceptQuery(request.getParameterMap());
        ExecutionContext context = runtimeContextService.currentContext(request);

        List<ConceptRecord> records = new ArrayList<>();
        ConceptPage page;
        try {
            page = conceptGateway.query(new ConceptQueryRequest(
                    document.concept(), context.tenantId(),
                    new ConceptQuery(baseQuery.filters(), baseQuery.sorts(), 0, RENDER_PAGE_SIZE)), context);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        int offset = 0;
        while (true) {
            records.addAll(page.items());
            if (records.size() > MAX_DOCUMENT_ROWS) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Document '" + document.name() + "' matched more than " + MAX_DOCUMENT_ROWS
                                + " rows. A PDF must be materialized in full before it can be rendered, so narrow "
                                + "the query (filters) or use the CSV export, which streams without this limit.");
            }
            offset += page.items().size();
            if (!page.hasMore() || page.items().isEmpty()) {
                break;
            }
            page = conceptGateway.query(new ConceptQueryRequest(
                    document.concept(), context.tenantId(),
                    new ConceptQuery(baseQuery.filters(), baseQuery.sorts(), offset, RENDER_PAGE_SIZE)), context);
        }

        List<String> columns = resolveColumns(records);
        String html = buildPrintHtml(document, columns, records);

        byte[] pdfBytes;
        try {
            pdfBytes = documentRenderer.render(html, renderOptionsFor(document));
        } catch (DocumentRenderContract.DocumentRenderException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to render document: " + ex.getMessage(), ex);
        }

        // Headers set only after rendering succeeded, before any body byte is written -- the same
        // no-half-written-response discipline exportCsv follows.
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "inline; filename=\"" + pdfFilename(document.name()) + "\"");
        response.setContentLength(pdfBytes.length);
        response.getOutputStream().write(pdfBytes);
        response.getOutputStream().flush();
    }

    /**
     * R5.7 (Roadmap Wave 1 2026-08-19): the last mile for an {@code aggregate}-bound document -- the
     * {@code documents[]} shape a header band plus one or more line-item bands renders, versus the
     * flat concept-query shape {@link #renderPdf} already served. Loads the aggregate tree the exact
     * same way {@link AggregateApiController#load} does, resolves the declared {@code logo} field (if
     * any) into an inline {@code data:} URI, then dispatches to the {@code documentRender} capability's
     * {@code renderAggregate} operation -- the same {@link DocumentRenderContract} bean this controller
     * already holds, since both shipped adapters ({@code document-render-inproc}, {@code
     * document-render-stub}) implement {@link CapabilityAdapter} for this capability. Deliberately NOT
     * routed through {@link com.npdev.kernel.ports.CapabilityDispatcher} / the model's declared
     * {@code capabilities}/{@code capabilityBindings} -- that registry is populated only from bindings
     * an app's model explicitly declares (for flow {@code capabilityCall} steps), and a document should
     * render without requiring an author to also wire an unrelated capability binding.
     */
    @GetMapping("/{document}/aggregate/{rootId}/render.pdf")
    public void renderAggregatePdf(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable("document") String documentName,
            @PathVariable("rootId") String rootId
    ) throws IOException {
        CompiledDocument document = findDocument(documentName);
        if (document.aggregate() == null || document.aggregate().isBlank() || document.bands().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Document '" + document.name() + "' is not aggregate-bound (no 'aggregate'/'bands' "
                            + "declared) -- use GET /{document}/render.pdf instead.");
        }
        if (aggregateRuntime == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "aggregate runtime not configured");
        }

        ExecutionContext context = runtimeContextService.currentContext(request);
        Map<String, Object> tree;
        try {
            tree = aggregateRuntime.load(document.aggregate(), rootId, context);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }

        String logoDataUri = resolveLogoDataUri(document.logo(), tree);
        Map<String, Object> payload = buildAggregatePayload(document, tree, logoDataUri);

        if (!(documentRenderer instanceof CapabilityAdapter capabilityAdapter)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "configured document renderer does not support aggregate documents");
        }
        CapabilityResult result = capabilityAdapter.invoke(
                new CapabilityCall("documentRender", "renderAggregate", payload), Map.of());
        if (!result.ok()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to render document: " + result.error().message());
        }
        Object value = result.value();
        if (!(value instanceof Map<?, ?> resultMap) || !(resultMap.get("contentBase64") instanceof String contentBase64)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "documentRender.renderAggregate returned an unexpected result shape");
        }
        byte[] pdfBytes = Base64.getDecoder().decode(contentBase64);

        // Headers set only after rendering succeeded, before any body byte is written -- the same
        // no-half-written-response discipline renderPdf/exportCsv follow.
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "inline; filename=\"" + pdfFilename(document.name()) + "\"");
        response.setContentLength(pdfBytes.length);
        response.getOutputStream().write(pdfBytes);
        response.getOutputStream().flush();
    }

    /**
     * Resolves a declared {@code logo.field} against the already-loaded aggregate tree into an inline
     * {@code data:} URI, or {@code null} if there is nothing safe to render.
     *
     * <p><b>Never fetches a URL</b> (R6-F3, mirrored by {@code AggregateDocumentHtmlBuilder} and
     * {@code DocumentRenderInProcAdapter.DENY_EXTERNAL_URIS}, which refuse anything else at render
     * time regardless): the field's stored value is accepted in exactly two shapes --
     * <ul>
     *   <li>a plain string that is ALREADY an inline {@code data:} URI (e.g. seeded/authored directly
     *       on the record) -- returned as-is, never otherwise interpreted as a path or URL;</li>
     *   <li>a {@code file}-typed field's stored handle map ({@code storeId}/{@code key}, the shape
     *       {@link com.finalexec.api.FileUploadController#upload} persists) -- resolved through {@link
     *       FileStoreContract#head}/{@link FileStoreContract#get}, the platform's own internal file
     *       store, and base64-encoded into a {@code data:<contentType>;base64,...} URI. This is a local
     *       store lookup keyed by an opaque handle, never an outbound request.</li>
     * </ul>
     * Any other shape (missing field, unresolvable handle, a plain non-{@code data:} string such as an
     * {@code http(s)://} URL) resolves to {@code null} -- the document renders without a logo rather
     * than fail outright, and rather than ever attempt a fetch.
     */
    private String resolveLogoDataUri(CompiledDocumentLogo logo, Map<String, Object> tree) {
        if (logo == null || logo.field() == null || logo.field().isBlank()) {
            return null;
        }
        Object value = tree.get(logo.field());
        if (value instanceof String text) {
            return text.regionMatches(true, 0, "data:", 0, 5) ? text : null;
        }
        if (value instanceof Map<?, ?> handleMap) {
            Object storeId = handleMap.get("storeId");
            Object key = handleMap.get("key");
            if (storeId == null || key == null || fileStore == null) {
                return null;
            }
            try {
                FileHandle handle = fileStore.head(String.valueOf(storeId), String.valueOf(key));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                fileStore.get(handle, out);
                String contentType = handle.contentType() == null ? "application/octet-stream" : handle.contentType();
                return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(out.toByteArray());
            } catch (NoSuchElementException ex) {
                return null; // handle no longer resolvable -- render without a logo, not a failed document
            }
        }
        return null;
    }

    /**
     * Builds the untyped payload {@code AggregateDocumentPayload.parse} (document-render-inproc,
     * package-private) expects: the loaded tree, the document's compiled band/field declarations
     * flattened to plain maps, and the resolved logo -- deliberately untyped since the adapter that
     * parses it lives in a different module this controller does not depend on.
     */
    private static Map<String, Object> buildAggregatePayload(
            CompiledDocument document, Map<String, Object> tree, String logoDataUri) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", (document.title() == null || document.title().isBlank()) ? document.name() : document.title());
        payload.put("pageSize", document.pageSize());
        payload.put("marginMm", document.marginMm());
        payload.put("filename", pdfFilename(document.name()));
        payload.put("logoDataUri", logoDataUri);
        payload.put("tree", tree);

        List<Map<String, Object>> bands = new ArrayList<>();
        for (CompiledDocumentBand band : document.bands()) {
            Map<String, Object> bandMap = new LinkedHashMap<>();
            bandMap.put("name", band.name());
            bandMap.put("kind", band.kind());
            bandMap.put("collection", band.collection());
            bandMap.put("label", band.label());

            List<Map<String, Object>> fields = new ArrayList<>();
            for (CompiledPanelFieldBinding field : band.fields()) {
                Map<String, Object> fieldMap = new LinkedHashMap<>();
                fieldMap.put("field", field.field());
                String label = field.ui() == null ? null : field.ui().getLabel();
                fieldMap.put("label", (label == null || label.isBlank()) ? field.field() : label);
                fields.add(fieldMap);
            }
            bandMap.put("fields", fields);
            bands.add(bandMap);
        }
        payload.put("bands", bands);
        return payload;
    }

    private CompiledDocument findDocument(String documentName) {
        return modelProvider.compiledModel().getDocuments().stream()
                .filter(candidate -> candidate.name().equals(documentName))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No document declared named '" + documentName + "'"));
    }

    private static DocumentRenderContract.RenderOptions renderOptionsFor(CompiledDocument document) {
        DocumentRenderContract.PageSize pageSize = "Letter".equalsIgnoreCase(document.pageSize())
                ? DocumentRenderContract.PageSize.LETTER
                : DocumentRenderContract.PageSize.A4;
        double marginMm = document.marginMm() == null ? 20.0 : document.marginMm();
        return new DocumentRenderContract.RenderOptions(pageSize, marginMm);
    }

    private static String pdfFilename(String documentName) {
        String safe = documentName == null ? "document" : documentName.replaceAll("[^A-Za-z0-9_-]", "_");
        return (safe.isBlank() ? "document" : safe) + ".pdf";
    }

    /** Mirrors {@link ConceptQueryController}'s own {@code resolveColumns} (a private helper there,
     * not shared -- duplicated rather than exposed, since the two controllers' column-resolution
     * needs are identical but independent). */
    private static List<String> resolveColumns(List<ConceptRecord> records) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add("id");
        if (!records.isEmpty()) {
            columns.addAll(records.get(0).data().keySet());
        }
        return List.copyOf(columns);
    }

    /**
     * Builds the same print-document shape LNCH-10 Slice 2's {@code printPanel()}/print.css produce
     * (title/meta/table/footer) -- inlined here rather than fetched, since this renders server-side
     * with no browser DOM available. Kept in sync by hand with
     * {@code business-ui-style.mustache}'s {@code .print-*} rules; this is the seam the Slice 2 plan
     * named for Slice 3 to reuse.
     */
    private static String buildPrintHtml(CompiledDocument document, List<String> columns, List<ConceptRecord> records) {
        String title = (document.title() == null || document.title().isBlank()) ? document.name() : document.title();
        StringBuilder html = new StringBuilder();
        html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html>")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style>")
                .append("body { font-family: Helvetica, Arial, sans-serif; color: #18202a; }")
                .append(".print-header { margin-bottom: 16px; border-bottom: 2px solid #18202a; padding-bottom: 8px; }")
                .append(".print-header h1 { margin: 0 0 4px 0; font-size: 20px; }")
                .append(".print-meta { font-size: 12px; color: #4a5568; }")
                .append("table.print-table { width: 100%; border-collapse: collapse; }")
                .append("table.print-table th, table.print-table td { border: 1px solid #18202a; padding: 6px 8px; text-align: left; font-size: 12px; }")
                .append("table.print-table th { background-color: #eef1f5; }")
                .append("table.print-table tr { page-break-inside: avoid; }")
                .append(".print-footer { margin-top: 12px; font-size: 12px; color: #4a5568; text-align: right; }")
                .append("</style></head><body>")
                .append("<div class=\"print-document\">")
                .append("<div class=\"print-header\"><h1>").append(escape(title)).append("</h1>")
                .append("<div class=\"print-meta\">Printed ")
                .append(escape(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))))
                .append("</div></div>")
                .append("<table class=\"print-table\"><thead><tr>");
        for (String column : columns) {
            html.append("<th>").append(escape(column)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        if (records.isEmpty()) {
            html.append("<tr><td colspan=\"").append(Math.max(1, columns.size())).append("\">No records</td></tr>");
        } else {
            for (ConceptRecord record : records) {
                html.append("<tr>");
                for (String column : columns) {
                    Object value = "id".equals(column) ? record.id() : record.data().get(column);
                    html.append("<td>").append(escape(value == null ? "" : String.valueOf(value))).append("</td>");
                }
                html.append("</tr>");
            }
        }
        html.append("</tbody></table>")
                .append("<div class=\"print-footer\">Total: ").append(records.size()).append(" record(s)</div>")
                .append("</div></body></html>");
        return html.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
