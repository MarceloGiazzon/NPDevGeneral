package com.finalexec.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VIEW-1: one query per readable concept through the gateway; unreadable concepts are skipped. */
class GlobalSearchServiceTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.search", "version": "1.0",
          "concepts": [
            { "name": "Customer", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "name", "type": "string" },
              { "name": "email", "type": "string" },
              { "name": "age", "type": "integer" },
              { "name": "taxCode", "type": "string", "sensitive": true } ] },
            { "name": "Invoice", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "number", "type": "string" },
              { "name": "memo", "type": "string" } ] },
            { "name": "Secret", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "title", "type": "string" } ] },
            { "name": "Counter", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "total", "type": "integer" } ] }
          ]
        }
        """;

    private static final ExecutionContext CONTEXT = ExecutionContext.of("t1", "alice");

    private static CompiledModel model() throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
    }

    /** Records every query it is asked and answers with fixed rows; "Secret" is unreadable. */
    private static final class FakeGateway implements ConceptGateway {
        final List<ConceptQueryRequest> asked = new ArrayList<>();
        final Set<String> denied = new HashSet<>(Set.of("Secret"));
        final Map<String, List<ConceptRecord>> rows;

        FakeGateway(Map<String, List<ConceptRecord>> rows) {
            this.rows = rows;
        }

        @Override
        public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
            asked.add(request);
            if (denied.contains(request.conceptName())) {
                throw new ConceptGatewayAccessDeniedException("READ_DENIED", "no read access");
            }
            List<ConceptRecord> items = rows.getOrDefault(request.conceptName(), List.of());
            return new ConceptPage(items, items.size(), false);
        }

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            return Optional.empty();
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            return List.of();
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static ConceptRecord record(String concept, String id, Map<String, Object> data) {
        return new ConceptRecord(concept, id, "t1", data);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("results");
    }

    @Test
    void aOneCharacterQueryAsksNothing() throws Exception {
        FakeGateway gateway = new FakeGateway(Map.of());
        GlobalSearchService service = new GlobalSearchService(() -> safeModel(), () -> gateway);

        assertTrue(results(service.search("a", 5, CONTEXT)).isEmpty());
        assertTrue(gateway.asked.isEmpty());
    }

    @Test
    void searchesEveryConceptWithSearchableFieldsAndSkipsTheOnesTheCallerCannotRead() throws Exception {
        FakeGateway gateway = new FakeGateway(Map.of(
                "Customer", List.of(record("Customer", "c1", Map.of("name", "Acme Corp", "email", "x@acme.io"))),
                "Invoice", List.of(record("Invoice", "i1", Map.of("number", "INV-1", "memo", "for Acme")))));
        GlobalSearchService service = new GlobalSearchService(() -> safeModel(), () -> gateway);

        List<Map<String, Object>> found = results(service.search("acme", 5, CONTEXT));

        assertEquals(2, found.size());
        assertEquals(List.of("Customer", "Invoice", "Secret"),
                gateway.asked.stream().map(ConceptQueryRequest::conceptName).toList(),
                "Counter has no string field so it is never asked; Secret is asked and denied");
        assertFalse(found.stream().anyMatch(hit -> "Secret".equals(hit.get("concept"))));
    }

    @Test
    void theQueryIsOneOrGroupPerSearchableFieldWithoutIdOrSensitiveFields() throws Exception {
        FakeGateway gateway = new FakeGateway(Map.of());
        GlobalSearchService service = new GlobalSearchService(() -> safeModel(), () -> gateway);

        service.search("acme", 7, CONTEXT);

        ConceptQueryRequest customer = gateway.asked.stream()
                .filter(r -> "Customer".equals(r.conceptName())).findFirst().orElseThrow();
        assertEquals("t1", customer.tenantId());
        assertEquals(7, customer.query().limit());
        ConceptQuery.Filter or = customer.query().filters().get(0);
        assertEquals(ConceptQuery.Operator.OR_GROUPS, or.operator());
        @SuppressWarnings("unchecked")
        List<List<ConceptQuery.Filter>> groups = (List<List<ConceptQuery.Filter>>) or.value();
        assertEquals(List.of("email", "name"),
                groups.stream().map(g -> g.get(0).field()).sorted().toList(),
                "string fields only: not id, not the sensitive taxCode, not the integer age");
        assertTrue(groups.stream().allMatch(g -> g.size() == 1
                && g.get(0).operator() == ConceptQuery.Operator.CONTAINS && "acme".equals(g.get(0).value())));
    }

    @Test
    void exactLabelMatchesRankBeforePrefixMatchesBeforeTheRest() throws Exception {
        FakeGateway gateway = new FakeGateway(Map.of(
                "Customer", List.of(
                        record("Customer", "c1", Map.of("name", "Big Acme Ltd")),
                        record("Customer", "c2", Map.of("name", "Acme Corp")),
                        record("Customer", "c3", Map.of("name", "acme")))));
        GlobalSearchService service = new GlobalSearchService(() -> safeModel(), () -> gateway);

        List<Map<String, Object>> found = results(service.search("acme", 5, CONTEXT));

        assertEquals(List.of("c3", "c2", "c1"), found.stream().map(hit -> hit.get("id")).toList());
        assertEquals("name", found.get(0).get("matchedField"));
        assertEquals("acme", found.get(0).get("label"));
    }

    @Test
    void theResponseIsCappedAtFiftyRows() throws Exception {
        List<ConceptRecord> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(record("Customer", "c" + i, Map.of("name", "acme " + i)));
            many.add(record("Invoice", "i" + i, Map.of("number", "acme " + i)));
            many.add(record("Secret", "s" + i, Map.of("title", "acme " + i)));
        }
        FakeGateway gateway = new FakeGateway(Map.of(
                "Customer", many.stream().filter(r -> r.conceptName().equals("Customer")).toList(),
                "Invoice", many.stream().filter(r -> r.conceptName().equals("Invoice")).toList(),
                "Secret", many.stream().filter(r -> r.conceptName().equals("Secret")).toList()));
        gateway.denied.clear();
        GlobalSearchService service = new GlobalSearchService(() -> safeModel(), () -> gateway);

        assertEquals(GlobalSearchService.MAX_TOTAL, results(service.search("acme", 20, CONTEXT)).size());
    }

    private static CompiledModel safeModel() {
        try {
            return model();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
