package com.npdev.kernel.concepts;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Friction #5: "beers to drink" = cells needed per (mosaic, cap) minus the caller's owned quantity. */
class ConceptAggregateLookupsTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void mergesLookupByKeyDefaultsMissingSumToZeroAndComputesTheDifference() {
        UUID red = UUID.randomUUID();
        List<Map<String, Object>> base = List.of(
                row("mosaicId.code", "M-1", "capId", red, "needed", 10L),
                row("mosaicId.code", "M-1", "capId", "blue", "needed", 3L));
        List<ConceptAggregateQuery> seen = new ArrayList<>();
        List<String> concepts = new ArrayList<>();

        List<Map<String, Object>> out = ConceptAggregateLookups.apply(base,
                List.of(new ConceptAggregateLookups.Lookup("owned", "CollectionItem", "sum", "quantity",
                        Map.of("capId", "capId"), List.of())),
                List.of(new ConceptAggregateLookups.Computed("toDrink", "needed - owned")),
                (concept, query) -> {
                    concepts.add(concept);
                    seen.add(query);
                    // a different store may hand the reference id back as a String, not a UUID
                    return new ConceptAggregateResult(List.of(row("capId", red.toString(), "owned", new BigDecimal("4"))));
                });

        assertEquals(List.of("CollectionItem"), concepts);
        assertEquals(List.of(new ConceptAggregateQuery.GroupByField("capId", null)), seen.get(0).groupBy());
        assertEquals(List.of(new ConceptAggregateQuery.AggregateFunction("owned", "sum", "quantity")),
                seen.get(0).aggregates());
        assertEquals(new BigDecimal("4"), out.get(0).get("owned"));
        assertEquals(6L, out.get(0).get("toDrink"));
        assertEquals(0L, out.get(1).get("owned"));
        assertEquals(3L, out.get(1).get("toDrink"));
        assertEquals(List.of("mosaicId.code", "capId", "needed", "owned", "toDrink"),
                new ArrayList<>(out.get(0).keySet()));
    }

    @Test
    void missingMaxIsNullAndHavingSortsOnTheComputedColumn() {
        List<Map<String, Object>> base = List.of(
                row("capId", "a", "needed", 2L),
                row("capId", "b", "needed", 9L),
                row("capId", "c", "needed", 5L));
        List<Map<String, Object>> out = ConceptAggregateLookups.apply(base,
                List.of(new ConceptAggregateLookups.Lookup("owned", "Owned", "sum", "quantity", Map.of("capId", "capId"), List.of()),
                        new ConceptAggregateLookups.Lookup("best", "Owned", "max", "quantity", Map.of("capId", "capId"), List.of())),
                List.of(new ConceptAggregateLookups.Computed("toDrink", "needed - owned")),
                (concept, query) -> new ConceptAggregateResult(List.of(
                        row("capId", "a", query.aggregates().get(0).outputName(), 5L),
                        row("capId", "c", query.aggregates().get(0).outputName(), 1L))));

        assertNull(out.get(1).get("best"));
        List<Map<String, Object>> ranked = ConceptAggregateEngine.applyHavingSortAndLimit(out,
                List.of(new ConceptQuery.Filter("toDrink", ConceptQuery.Operator.GT, 0L)),
                List.of(new ConceptQuery.Sort("toDrink", true)), 10);
        assertEquals(List.of("b", "c"), ranked.stream().map(r -> r.get("capId")).toList());
    }
}
