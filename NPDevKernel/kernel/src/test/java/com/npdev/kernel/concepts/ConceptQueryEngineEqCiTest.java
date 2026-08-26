package com.npdev.kernel.concepts;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-28 (2026-08-25 remediation plan W2.2): {@link ConceptQuery.Operator#EQ_CI} must reproduce, in
 * memory, the exact same rule the generated CRUD's own (pre-pushdown) {@code uniqueValuesEqual} and
 * {@code ConceptStore#uniqueValuesCollide} apply -- trimmed, case-insensitive, string-cast equality,
 * regardless of the field's actual stored type. This is the in-memory half of the fix; the SQL half
 * is {@code JdbcBusinessConceptStoreEqCiTest} in NPDevRuntimeHost.
 */
class ConceptQueryEngineEqCiTest {

    private static ConceptRecord row(String id, Object supplierId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("supplierId", supplierId);
        return new ConceptRecord("Order", id, "t1", data);
    }

    private static List<ConceptRecord> apply(List<ConceptRecord> records, Object value) {
        ConceptQuery query = new ConceptQuery(
                List.of(ConceptQuery.Filter.eqCaseInsensitive("supplierId", value)),
                List.of(), 0, ConceptQuery.MAX_LIMIT);
        return ConceptQueryEngine.apply(records, query).items();
    }

    @Test
    void exactCaseMatches() {
        List<ConceptRecord> result = apply(List.of(row("r1", "SUP-1")), "SUP-1");
        assertEquals(1, result.size());
    }

    @Test
    void differentCaseStillMatches() {
        List<ConceptRecord> result = apply(List.of(row("r1", "SUP-1")), "sup-1");
        assertEquals(1, result.size(), "case-insensitive: 'sup-1' must match stored 'SUP-1'");
    }

    @Test
    void leadingAndTrailingWhitespaceOnEitherSideIsIgnored() {
        assertEquals(1, apply(List.of(row("r1", "  SUP-1  ")), "SUP-1").size());
        assertEquals(1, apply(List.of(row("r1", "SUP-1")), "  sup-1  ").size());
    }

    @Test
    void nonMatchingValueIsExcluded() {
        List<ConceptRecord> result = apply(List.of(row("r1", "SUP-1"), row("r2", "SUP-2")), "sup-2");
        assertEquals(1, result.size());
        assertEquals("r2", result.get(0).id());
    }

    @Test
    void nonStringStoredValueComparesByStringRepresentation() {
        // A reference field can legitimately be stored as a non-String (e.g. a UUID or long) while
        // the incoming comparison value (always a URL path segment at the listBy* call site) is a
        // String -- EQ_CI must cast both sides to text, not require the stored type to match.
        List<ConceptRecord> result = apply(List.of(row("r1", 42L)), "42");
        assertEquals(1, result.size());
    }

    @Test
    void nullStoredValueNeverMatches() {
        assertTrue(apply(List.of(row("r1", null)), "sup-1").isEmpty());
    }

    @Test
    void nullComparisonValueNeverMatches() {
        assertTrue(apply(List.of(row("r1", "SUP-1")), null).isEmpty());
    }
}
