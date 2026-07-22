package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.generated.runtime.model.NPDevModelProvider;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.DocumentRenderContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

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
 * rendering (the HTML-to-PDF adapter needs the complete document up front) -- bounded the same way
 * CSV's page loop is, at {@link ConceptQuery#MAX_LIMIT} rows per page while accumulating.
 */
@RestController
@RequestMapping({"/api/v1/documents", "/api/documents"})
public class DocumentRenderController {

    private static final int RENDER_PAGE_SIZE = ConceptQuery.MAX_LIMIT;

    private final NPDevModelProvider modelProvider;
    private final RuntimeContextService runtimeContextService;
    private final ConceptGateway conceptGateway;
    private final DocumentRenderContract documentRenderer;

    public DocumentRenderController(
            NPDevModelProvider modelProvider,
            RuntimeContextService runtimeContextService,
            ConceptGateway conceptGateway,
            DocumentRenderContract documentRenderer
    ) {
        this.modelProvider = modelProvider;
        this.runtimeContextService = runtimeContextService;
        this.conceptGateway = conceptGateway;
        this.documentRenderer = documentRenderer;
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
