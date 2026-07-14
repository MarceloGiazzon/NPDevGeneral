package com.finalexec.api;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
}
