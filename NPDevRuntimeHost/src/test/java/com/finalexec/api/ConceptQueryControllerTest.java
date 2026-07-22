package com.finalexec.api;

import com.npdev.kernel.concepts.ConceptQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LNCH-5: the HTTP-parameter -> {@link ConceptQuery} mapping behind the paged concept-query endpoint.
 * Kept a pure static function so it verifies in the hermetic gate without booting the app.
 */
class ConceptQueryControllerTest {

    @Test
    void offsetLimitSortAndArbitraryFieldFiltersMapToConceptQuery() {
        ConceptQuery query = ConceptQueryController.parseConceptQuery(Map.of(
                "offset", new String[]{"20"},
                "limit", new String[]{"10"},
                "sort", new String[]{"createdAt"},
                "direction", new String[]{"desc"},
                "status", new String[]{"open"},
                "priority", new String[]{"3"}));

        assertEquals(20, query.offset());
        assertEquals(10, query.limit());
        assertEquals(1, query.sorts().size());
        assertEquals("createdAt", query.sorts().get(0).field());
        assertEquals(true, query.sorts().get(0).descending());
        assertEquals(2, query.filters().size(), "status and priority become equality filters");
        assertEquals(ConceptQuery.Operator.EQ, query.filters().get(0).operator());
    }

    @Test
    void springStylePageAndSizeConvertToOffsetLimit() {
        ConceptQuery query = ConceptQueryController.parseConceptQuery(Map.of(
                "page", new String[]{"3"},
                "size", new String[]{"25"}));
        assertEquals(75, query.offset(), "page 3 * size 25");
        assertEquals(25, query.limit());
    }

    @Test
    void reservedParamsAreNotTreatedAsFilters() {
        ConceptQuery query = ConceptQueryController.parseConceptQuery(Map.of(
                "page", new String[]{"0"},
                "size", new String[]{"50"},
                "sort", new String[]{"name"},
                "direction", new String[]{"asc"},
                "filter", new String[]{"ignored"},
                "where", new String[]{"ignored"}));
        assertEquals(0, query.filters().size());
    }

    @Test
    void limitIsCappedAndDefaulted() {
        assertEquals(ConceptQuery.MAX_LIMIT,
                ConceptQueryController.parseConceptQuery(Map.of("limit", new String[]{"100000"})).limit());
        assertEquals(ConceptQuery.DEFAULT_LIMIT,
                ConceptQueryController.parseConceptQuery(Map.of()).limit());
    }

    /** LNCH-10 slice 1: {@code exportCsv}'s row-formatting helper -- a value containing a
     * comma, quote, or newline gets RFC4180-quoted (internal quotes doubled); plain values
     * pass through unquoted, and {@code null} becomes an empty field. */
    @Test
    void csvRowQuotesOnlyValuesThatNeedIt() {
        java.util.List<Object> values = java.util.Arrays.asList(
                "plain", "has,comma", "has\"quote", "has\nnewline", null);
        assertEquals(
                "plain,\"has,comma\",\"has\"\"quote\",\"has\nnewline\",\r\n",
                ConceptQueryController.toCsvRow(values)
        );
    }
}
