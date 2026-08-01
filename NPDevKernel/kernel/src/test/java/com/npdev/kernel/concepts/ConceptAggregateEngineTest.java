package com.npdev.kernel.concepts;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): the in-memory reference tests for
 * {@link ConceptAggregateEngine} -- the DoD explicitly requires groupBy, all five {@code fn}
 * values, and at least one {@code bucket} to be exercised, including a month bucket spanning a
 * year boundary.
 */
class ConceptAggregateEngineTest {

    private static ConceptRecord shipment(String id, String warehouseId, String shippedAt, int units) {
        return new ConceptRecord("WidgetShipmentEvent", id, "tenant-a",
                Map.of("warehouseId", warehouseId, "shippedAt", shippedAt, "unitsShipped", units));
    }

    @Test
    void groupsByPlainFieldAndComputesAllFiveAggregateFunctions() {
        List<ConceptRecord> records = List.of(
                shipment("s1", "WH1", "2026-01-05", 10),
                shipment("s2", "WH1", "2026-01-10", 20),
                shipment("s3", "WH2", "2026-01-06", 5)
        );
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(),
                List.of(new ConceptAggregateQuery.GroupByField("warehouseId", null)),
                List.of(
                        new ConceptAggregateQuery.AggregateFunction("totalUnits", "sum", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("avgUnits", "avg", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("minUnits", "min", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("maxUnits", "max", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("eventCount", "count", null)
                ),
                List.of(), List.of(new ConceptQuery.Sort("warehouseId", false)), null);

        ConceptAggregateResult result = ConceptAggregateEngine.apply(records, query);

        assertEquals(2, result.rows().size());
        Map<String, Object> wh1 = result.rows().get(0);
        assertEquals("WH1", wh1.get("warehouseId"));
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) wh1.get("totalUnits")));
        assertEquals(0, new BigDecimal("15").compareTo((BigDecimal) wh1.get("avgUnits")));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) wh1.get("minUnits")));
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) wh1.get("maxUnits")));
        assertEquals(2L, wh1.get("eventCount"));

        Map<String, Object> wh2 = result.rows().get(1);
        assertEquals("WH2", wh2.get("warehouseId"));
        assertEquals(1L, wh2.get("eventCount"));
    }

    @Test
    void monthBucketGroupsSeparatelyAcrossAYearBoundary() {
        List<ConceptRecord> records = List.of(
                shipment("s1", "WH1", "2025-12-20", 3),
                shipment("s2", "WH1", "2025-12-31", 4),
                shipment("s3", "WH1", "2026-01-01", 5),
                shipment("s4", "WH1", "2026-01-15", 6)
        );
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(),
                List.of(new ConceptAggregateQuery.GroupByField("shippedAt", "month")),
                List.of(new ConceptAggregateQuery.AggregateFunction("totalUnits", "sum", "unitsShipped")),
                List.of(), List.of(new ConceptQuery.Sort("shippedAt", false)), null);

        ConceptAggregateResult result = ConceptAggregateEngine.apply(records, query);

        assertEquals(2, result.rows().size());
        assertEquals("2025-12", result.rows().get(0).get("shippedAt"));
        assertEquals(0, new BigDecimal("7").compareTo((BigDecimal) result.rows().get(0).get("totalUnits")));
        assertEquals("2026-01", result.rows().get(1).get("shippedAt"));
        assertEquals(0, new BigDecimal("11").compareTo((BigDecimal) result.rows().get(1).get("totalUnits")));
    }

    @Test
    void noGroupByStillReturnsExactlyOneRowForAKpiTotal() {
        List<ConceptRecord> records = List.of(
                shipment("s1", "WH1", "2026-01-05", 10),
                shipment("s2", "WH2", "2026-01-06", 20)
        );
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(), List.of(),
                List.of(new ConceptAggregateQuery.AggregateFunction("totalUnits", "sum", "unitsShipped")),
                List.of(), List.of(), null);

        ConceptAggregateResult result = ConceptAggregateEngine.apply(records, query);

        assertEquals(1, result.rows().size());
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) result.rows().get(0).get("totalUnits")));
    }

    @Test
    void havingFiltersOutGroupsAfterAggregation() {
        List<ConceptRecord> records = List.of(
                shipment("s1", "WH1", "2026-01-05", 10),
                shipment("s2", "WH2", "2026-01-06", 1)
        );
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(),
                List.of(new ConceptAggregateQuery.GroupByField("warehouseId", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("totalUnits", "sum", "unitsShipped")),
                List.of(new ConceptQuery.Filter("totalUnits", ConceptQuery.Operator.GT, 5)),
                List.of(), null);

        ConceptAggregateResult result = ConceptAggregateEngine.apply(records, query);

        assertEquals(1, result.rows().size());
        assertEquals("WH1", result.rows().get(0).get("warehouseId"));
    }

    @Test
    void limitCapsRowsAfterSort() {
        List<ConceptRecord> records = List.of(
                shipment("s1", "WH1", "2026-01-05", 10),
                shipment("s2", "WH2", "2026-01-05", 20),
                shipment("s3", "WH3", "2026-01-05", 30)
        );
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(),
                List.of(new ConceptAggregateQuery.GroupByField("warehouseId", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("totalUnits", "sum", "unitsShipped")),
                List.of(), List.of(new ConceptQuery.Sort("totalUnits", true)), 2);

        ConceptAggregateResult result = ConceptAggregateEngine.apply(records, query);

        assertEquals(2, result.rows().size());
        assertEquals("WH3", result.rows().get(0).get("warehouseId"));
        assertEquals("WH2", result.rows().get(1).get("warehouseId"));
    }

    @Test
    void avgOfEmptyGroupIsNullNotDivideByZero() {
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(new ConceptQuery.Filter("unitsShipped", ConceptQuery.Operator.GT, 1000)),
                List.of(),
                List.of(new ConceptAggregateQuery.AggregateFunction("avgUnits", "avg", "unitsShipped")),
                List.of(), List.of(), null);

        ConceptAggregateResult result = ConceptAggregateEngine.apply(
                List.of(shipment("s1", "WH1", "2026-01-05", 10)), query);

        assertEquals(1, result.rows().size());
        assertNull(result.rows().get(0).get("avgUnits"));
    }

    /**
     * Move 10 B1 live verification (MOVE10_AI_LOWCODE_PLAN Part B DoD: "in-memory adapter and JDBC
     * adapter produce identical results for the same query"): the EXACT 7 records seeded into the
     * live {@code aggregate-query-verify-h2local} app (REST: {@code GET
     * /api/queries/ShippedUnitsByWarehouseMonth}) and the EXACT rows a hand-written SQL {@code GROUP
     * BY} cross-check returned directly against that app's H2 file. This engine (the in-memory/dev
     * adapter) must independently reproduce the SAME totals from the SAME raw rows the JDBC adapter
     * summed in SQL -- proving the two engines agree without needing a second live deployment.
     */
    @Test
    void inMemoryEngineMatchesTheLiveJdbcAdapterResultOnTheSameSeedData() {
        List<ConceptRecord> records = List.of(
                shipment("s1", "WH1", "2025-12-20", 10),
                shipment("s2", "WH1", "2025-12-31", 15),
                shipment("s3", "WH1", "2026-01-01", 7),
                shipment("s4", "WH1", "2026-01-15", 3),
                shipment("s5", "WH2", "2025-12-05", 100),
                shipment("s6", "WH2", "2026-01-20", 1),
                shipment("s7", "WH2", "2026-01-22", 1)
        );
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(new ConceptQuery.Filter("unitsShipped", ConceptQuery.Operator.GT, 0)),
                List.of(
                        new ConceptAggregateQuery.GroupByField("warehouseId", null),
                        new ConceptAggregateQuery.GroupByField("shippedAt", "month")),
                List.of(
                        new ConceptAggregateQuery.AggregateFunction("totalUnits", "sum", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("avgUnits", "avg", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("minUnits", "min", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("maxUnits", "max", "unitsShipped"),
                        new ConceptAggregateQuery.AggregateFunction("eventCount", "count", null)),
                List.of(new ConceptQuery.Filter("totalUnits", ConceptQuery.Operator.GT, 0)),
                List.of(new ConceptQuery.Sort("totalUnits", true)),
                500);

        ConceptAggregateResult result = ConceptAggregateEngine.apply(records, query);

        // Same rows, same order, same values the JDBC adapter returned live (REST) and the
        // hand-written SQL GROUP BY cross-check confirmed directly against the H2 file.
        assertEquals(4, result.rows().size());

        Map<String, Object> wh2Dec = result.rows().get(0);
        assertEquals("WH2", wh2Dec.get("warehouseId"));
        assertEquals("2025-12", wh2Dec.get("shippedAt"));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) wh2Dec.get("totalUnits")));
        assertEquals(1L, wh2Dec.get("eventCount"));

        Map<String, Object> wh1Dec = result.rows().get(1);
        assertEquals("WH1", wh1Dec.get("warehouseId"));
        assertEquals("2025-12", wh1Dec.get("shippedAt"));
        assertEquals(0, new BigDecimal("25").compareTo((BigDecimal) wh1Dec.get("totalUnits")));
        assertEquals(0, new BigDecimal("12.5").compareTo((BigDecimal) wh1Dec.get("avgUnits")));
        assertEquals(2L, wh1Dec.get("eventCount"));

        Map<String, Object> wh1Jan = result.rows().get(2);
        assertEquals("WH1", wh1Jan.get("warehouseId"));
        assertEquals("2026-01", wh1Jan.get("shippedAt"));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) wh1Jan.get("totalUnits")));
        assertEquals(2L, wh1Jan.get("eventCount"));

        Map<String, Object> wh2Jan = result.rows().get(3);
        assertEquals("WH2", wh2Jan.get("warehouseId"));
        assertEquals("2026-01", wh2Jan.get("shippedAt"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) wh2Jan.get("totalUnits")));
        assertEquals(2L, wh2Jan.get("eventCount"));
    }

    @Test
    void bucketLabelUsesIsoWeekNumbering() {
        assertEquals("2026-01", ConceptAggregateEngine.bucketLabel(java.time.LocalDate.of(2026, 1, 15), "month"));
        assertEquals("2026-Q1", ConceptAggregateEngine.bucketLabel(java.time.LocalDate.of(2026, 1, 15), "quarter"));
        assertEquals("2026", ConceptAggregateEngine.bucketLabel(java.time.LocalDate.of(2026, 1, 15), "year"));
        assertEquals("2026-01-15", ConceptAggregateEngine.bucketLabel(java.time.LocalDate.of(2026, 1, 15), "day"));
        assertTrue(ConceptAggregateEngine.bucketLabel(java.time.LocalDate.of(2026, 1, 1), "week").startsWith("2026-W"));
    }
}
