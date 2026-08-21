package com.finalexec.api;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LNCH-5: a server-side paged, filtered, sorted list over the generic concept surface, pushing the
 * window down to the store (SQL {@code LIMIT}/{@code OFFSET} on JDBC) via
 * {@link ConceptGateway#query} instead of the generated CRUD controller's fetch-all-then-slice. A
 * grid over a 100k-row concept therefore streams one page, not the whole table.
 *
 * <p>{@code GET /api/v1/concepts/{concept}/page} accepts {@code offset}/{@code limit} (or Spring-style
 * {@code page}/{@code size}), {@code sort} + {@code direction}, and every other query parameter as an
 * equality filter on that field. It returns {@code {items, total, hasMore, offset, limit}}. Tenant,
 * permission, and field-visibility enforcement are the gateway's, identical to a normal list; the
 * JDBC store rejects filter/sort fields that are not declared columns, so this is not an injection
 * surface.
 */
@RestController
@RequestMapping({"/api/v1/concepts", "/api/concepts"})
public class ConceptQueryController {

    private static final Set<String> RESERVED_PARAMS =
            Set.of("offset", "limit", "page", "size", "sort", "direction", "filter", "where");

    private final RuntimeContextService runtimeContextService;
    private final ConceptGateway conceptGateway;

    public ConceptQueryController(RuntimeContextService runtimeContextService, ConceptGateway conceptGateway) {
        this.runtimeContextService = runtimeContextService;
        this.conceptGateway = conceptGateway;
    }

    @GetMapping("/{concept}/page")
    public Map<String, Object> page(HttpServletRequest request, @PathVariable String concept) {
        if (conceptGateway == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "concept gateway not configured");
        }
        ConceptQuery query = parseConceptQuery(request.getParameterMap());
        ExecutionContext context = runtimeContextService.currentContext(request);
        try {
            ConceptPage result = conceptGateway.query(new ConceptQueryRequest(concept, context.tenantId(), query), context);
            List<Map<String, Object>> items = new ArrayList<>(result.items().size());
            for (ConceptRecord record : result.items()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", record.id());
                item.putAll(record.data());
                items.add(item);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", items);
            response.put("total", result.total());
            response.put("hasMore", result.hasMore());
            response.put("offset", query.offset());
            response.put("limit", query.limit());
            return response;
        } catch (IllegalArgumentException ex) {
            // Unknown concept or a filter/sort on a field the concept does not declare.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static final int EXPORT_PAGE_SIZE = ConceptQuery.MAX_LIMIT;

    /**
     * LNCH-10 slice 1: exports the concept's current filtered/sorted view as CSV -- same
     * {@code filter}/{@code sort}/{@code where}-shaped query params {@link #page} accepts (parsed
     * through the identical {@link #parseConceptQuery}), so "what the grid shows" and "what
     * exports" are provably the same query. Streams page-by-page through {@link ConceptGateway
     * #query} (bounded at {@link ConceptQuery#MAX_LIMIT} rows in memory at any one time, flushed
     * to the response after each page) rather than materializing the whole result set, so a
     * 100k-row concept exports without holding more than one page in the JVM at once -- the same
     * lesson LNCH-5's grid pagination already established for this store contract.
     *
     * <p>The first page is fetched (and any unknown-concept/field error surfaced as a normal 400)
     * before any response header is written, so a bad request never produces a half-written CSV
     * with a 200 status; every page after that is inherently trusted (same query, same concept).
     */
    @GetMapping("/{concept}/export.csv")
    public void exportCsv(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String concept
    ) throws IOException {
        if (conceptGateway == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "concept gateway not configured");
        }
        ConceptQuery baseQuery = parseConceptQuery(request.getParameterMap());
        ExecutionContext context = runtimeContextService.currentContext(request);

        ConceptQuery firstPageQuery = new ConceptQuery(baseQuery.filters(), baseQuery.sorts(), 0, EXPORT_PAGE_SIZE);
        ConceptPage firstPage;
        try {
            firstPage = conceptGateway.query(new ConceptQueryRequest(concept, context.tenantId(), firstPageQuery), context);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + csvFilename(concept) + "\"");
        PrintWriter writer = response.getWriter();

        List<String> columns = resolveColumns(firstPage.items());
        writer.write(toCsvRow(columns));

        ConceptPage page = firstPage;
        int offset = 0;
        while (true) {
            for (ConceptRecord record : page.items()) {
                writer.write(toCsvRow(rowValues(record, columns)));
            }
            writer.flush();
            offset += page.items().size();
            if (!page.hasMore() || page.items().isEmpty()) {
                break;
            }
            ConceptQuery nextPageQuery = new ConceptQuery(baseQuery.filters(), baseQuery.sorts(), offset, EXPORT_PAGE_SIZE);
            page = conceptGateway.query(new ConceptQueryRequest(concept, context.tenantId(), nextPageQuery), context);
        }
    }

    private static String csvFilename(String concept) {
        String safe = concept == null ? "export" : concept.replaceAll("[^A-Za-z0-9_-]", "_");
        return (safe.isBlank() ? "export" : safe) + ".csv";
    }

    /** {@code record.data()} already carries an "id" entry on at least the JDBC adapter (every
     * SELECT * column, including the id column, becomes a data() entry) -- a
     * {@link java.util.LinkedHashSet} dedupes that against the explicit leading "id" column
     * (found live: without this, the CSV header repeated "id" twice). */
    private static List<String> resolveColumns(List<ConceptRecord> sampleRecords) {
        java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>();
        columns.add("id");
        if (!sampleRecords.isEmpty()) {
            columns.addAll(sampleRecords.get(0).data().keySet());
        }
        return List.copyOf(columns);
    }

    private static List<Object> rowValues(ConceptRecord record, List<String> columns) {
        List<Object> values = new ArrayList<>(columns.size());
        for (String column : columns) {
            values.add("id".equals(column) ? record.id() : record.data().get(column));
        }
        return values;
    }

    /** Minimal RFC4180 quoting: a value containing a comma, quote, or newline is wrapped in
     * quotes with internal quotes doubled; everything else is written as-is. */
    /** Delegates to {@link CsvCells} -- see that class for why the encoding lives outside this
     * controller (this one imports com.npdev.generated.*, so it is not compiled in a bare-template
     * checkout, and the formula-injection defence must verify in every configuration). */
    static String toCsvRow(List<?> values) {
        return CsvCells.toCsvRow(values);
    }

    /**
     * Maps HTTP query parameters to a {@link ConceptQuery}. {@code offset}/{@code limit} (or
     * {@code page}/{@code size}) set the window; {@code sort}+{@code direction} the ordering; every
     * other parameter is an equality filter on the field of that name. Kept static and free of any
     * servlet/generated dependency so it is unit-testable in the hermetic gate.
     */
    static ConceptQuery parseConceptQuery(Map<String, String[]> params) {
        Integer size = optionalInt(params, "size");
        Integer page = optionalInt(params, "page");
        int limit = size != null ? size : intParam(params, "limit", ConceptQuery.DEFAULT_LIMIT);
        int offset;
        if (params.containsKey("offset")) {
            offset = intParam(params, "offset", 0);
        } else if (page != null) {
            offset = Math.max(0, page) * Math.max(1, limit);
        } else {
            offset = 0;
        }

        List<ConceptQuery.Sort> sorts = new ArrayList<>();
        String sortField = firstParam(params, "sort");
        if (sortField != null && !sortField.isBlank()) {
            boolean descending = "desc".equalsIgnoreCase(firstParam(params, "direction"))
                    || "descending".equalsIgnoreCase(firstParam(params, "direction"));
            sorts.add(new ConceptQuery.Sort(sortField, descending));
        }

        List<ConceptQuery.Filter> filters = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || RESERVED_PARAMS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String[] values = entry.getValue();
            if (values == null || values.length == 0 || values[0] == null || values[0].isBlank()) {
                continue;
            }
            filters.add(new ConceptQuery.Filter(key, ConceptQuery.Operator.EQ, values[0]));
        }
        return new ConceptQuery(filters, sorts, offset, limit);
    }

    private static String firstParam(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static int intParam(Map<String, String[]> params, String name, int fallback) {
        Integer value = optionalInt(params, name);
        return value == null ? fallback : value;
    }

    private static Integer optionalInt(Map<String, String[]> params, String name) {
        String raw = firstParam(params, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // --- R7.8: batched endpoints for bulk selection UI ---

    /**
     * R7.8: batch update — applies a set of field updates to multiple records atomically.
     * Each entry in the body specifies an id and a map of fields to update.
     */
    @PatchMapping("/{concept}/batch")
    public Map<String, Object> batchUpdate(
            HttpServletRequest request,
            @PathVariable String concept,
            @RequestBody List<Map<String, Object>> ops
    ) {
        if (conceptGateway == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "concept gateway not configured");
        }
        if (ops == null || ops.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch operations list must not be empty");
        }
        ExecutionContext context = runtimeContextService.currentContext(request);
        int succeeded = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            String id = op.get("id") == null ? null : op.get("id").toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) op.get("fields");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            if (id == null || fields == null || fields.isEmpty()) {
                result.put("status", "skipped");
                result.put("reason", "missing id or fields");
            } else {
                try {
                    conceptGateway.update(concept, id, fields, context);
                    result.put("status", "updated");
                    succeeded++;
                } catch (Exception e) {
                    result.put("status", "failed");
                    result.put("reason", e.getMessage());
                }
            }
            results.add(result);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("concept", concept);
        response.put("total", ops.size());
        response.put("succeeded", succeeded);
        response.put("results", results);
        return response;
    }

    /**
     * R7.8: batch delete — deletes multiple records by id.
     */
    @DeleteMapping("/{concept}/batch")
    public Map<String, Object> batchDelete(
            HttpServletRequest request,
            @PathVariable String concept,
            @RequestParam List<String> ids
    ) {
        if (conceptGateway == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "concept gateway not configured");
        }
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids must not be empty");
        }
        ExecutionContext context = runtimeContextService.currentContext(request);
        int succeeded = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            try {
                conceptGateway.delete(concept, id, context);
                result.put("status", "deleted");
                succeeded++;
            } catch (Exception e) {
                result.put("status", "failed");
                result.put("reason", e.getMessage());
            }
            results.add(result);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("concept", concept);
        response.put("total", ids.size());
        response.put("succeeded", succeeded);
        response.put("results", results);
        return response;
    }
}
