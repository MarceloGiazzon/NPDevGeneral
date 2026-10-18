package com.finalexec.search;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * VIEW-1: the Ctrl+K palette's server side. One call searches every concept the caller may read for a
 * text, instead of the browser sending one list request per concept.
 *
 * <p>Each concept is asked through {@link ConceptGateway#query} with an OR of case-insensitive
 * {@code CONTAINS} clauses, one per searchable field, so the store builds the dialect's own SQL
 * and the gateway applies exactly what a normal list does: read permission, row rules, tenant and
 * field visibility. A concept the caller may not read is skipped silently, so search can never show a
 * row the grid would not. No SQL is built here.</p>
 *
 * <p>Searchable fields per concept: its {@code string} and {@code enum} fields (the same fields the UI
 * manifest marks {@code filterable}), never the id and never a {@code sensitive} one, at most
 * {@value #MAX_FIELDS}, in declaration order. A concept with none is not searched.</p>
 */
public final class GlobalSearchService {

    private static final Logger LOG = Logger.getLogger(GlobalSearchService.class.getName());

    public static final int MIN_QUERY_CHARS = 2;
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;
    public static final int MAX_TOTAL = 50;
    static final int MAX_FIELDS = 6;

    private final Supplier<CompiledModel> model;
    private final Supplier<ConceptGateway> gateway;

    public GlobalSearchService(Supplier<CompiledModel> model, Supplier<ConceptGateway> gateway) {
        this.model = model;
        this.gateway = gateway;
    }

    /**
     * @param limit rows asked of each concept (clamped to 1..{@value #MAX_LIMIT}); the response is
     *              capped at {@value #MAX_TOTAL} rows overall
     * @return {@code {query, results:[{concept, id, label, matchedField, record}]}}; empty results for a
     *         query shorter than {@value #MIN_QUERY_CHARS} characters
     */
    public Map<String, Object> search(String query, int limit, ExecutionContext context) {
        String text = query == null ? "" : query.trim();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", text);
        List<Map<String, Object>> results = new ArrayList<>();
        response.put("results", results);
        CompiledModel compiled = model.get();
        ConceptGateway conceptGateway = gateway.get();
        if (text.length() < MIN_QUERY_CHARS || compiled == null || conceptGateway == null) {
            return response;
        }
        int perConcept = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));

        List<Hit> hits = new ArrayList<>();
        for (CompiledConcept concept : compiled.getConcepts()) {
            List<String> fields = searchableFields(concept);
            if (fields.isEmpty()) {
                continue;
            }
            ConceptQuery conceptQuery = new ConceptQuery(
                    List.of(orOfContains(fields, text)), List.of(), 0, perConcept);
            ConceptPage page;
            try {
                page = conceptGateway.query(new ConceptQueryRequest(concept.getName(), context.tenantId(), conceptQuery), context);
            } catch (ConceptGatewayAccessDeniedException denied) {
                continue;   // the caller may not read this concept: not an error, just not searched
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "Global search skipped concept " + concept.getName() + ": " + failure.getMessage());
                continue;
            }
            for (ConceptRecord record : page.items()) {
                hits.add(toHit(concept.getName(), fields, record, text));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::rank));   // stable: concept order is kept within a rank
        for (Hit hit : hits) {
            if (results.size() >= MAX_TOTAL) {
                break;
            }
            results.add(hit.toMap());
        }
        return response;
    }

    static List<String> searchableFields(CompiledConcept concept) {
        List<String> names = new ArrayList<>();
        for (CompiledField field : concept.getFieldsInDeclaredOrder()) {
            if (names.size() >= MAX_FIELDS) {
                break;
            }
            if (field.isId() || field.isSensitive() || "id".equalsIgnoreCase(field.getName())) {
                continue;
            }
            String type = field.getDslType() == null || field.getDslType().isBlank()
                    ? "string" : field.getDslType().trim().toLowerCase(Locale.ROOT);
            if ("string".equals(type) || "enum".equals(type)) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private static ConceptQuery.Filter orOfContains(List<String> fields, String text) {
        List<List<ConceptQuery.Filter>> groups = new ArrayList<>();
        for (String field : fields) {
            groups.add(List.of(new ConceptQuery.Filter(field, ConceptQuery.Operator.CONTAINS, text)));
        }
        return ConceptQuery.Filter.orGroups(groups);
    }

    private static Hit toHit(String concept, List<String> fields, ConceptRecord record, String text) {
        Map<String, Object> data = record.data() == null ? Map.of() : record.data();
        String label = null;
        String matched = null;
        String needle = text.toLowerCase(Locale.ROOT);
        for (String field : fields) {
            Object value = data.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            if (label == null && isNameLike(field)) {
                label = String.valueOf(value);
            }
            if (matched == null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(needle)) {
                matched = field;
            }
        }
        if (label == null) {   // no name-like field: the first searchable value reads best
            for (String field : fields) {
                Object value = data.get(field);
                if (value != null && !String.valueOf(value).isBlank()) {
                    label = String.valueOf(value);
                    break;
                }
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", record.id());
        row.putAll(data);
        String shown = label == null ? record.id() : label;
        return new Hit(concept, record.id(), shown, matched, row, rankOf(shown, needle));
    }

    /** A field a person reads as the record's name: name, title, label, displayName... */
    private static boolean isNameLike(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        return lower.contains("name") || lower.contains("title") || lower.contains("label");
    }

    /** 0 = the label is exactly the text, 1 = it starts with it, 2 = it only contains it elsewhere. */
    private static int rankOf(String label, String needle) {
        String lower = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (lower.equals(needle)) {
            return 0;
        }
        return lower.startsWith(needle) ? 1 : 2;
    }

    private record Hit(String concept, String id, String label, String matchedField,
                       Map<String, Object> record, int rank) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("concept", concept);
            out.put("id", id);
            out.put("label", label);
            out.put("matchedField", matchedField);
            out.put("record", record);
            return out;
        }
    }
}
