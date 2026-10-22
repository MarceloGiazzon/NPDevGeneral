package com.finalexec.npdev.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.npdev.kernel.concepts.ConceptRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** REG-277: a collection's declared orderBy orders the children that load and commit return. */
class AggregateRuntimeCollectionOrderByTest {

    private static ConceptRecord row(String id, Object sku, Object qty) {
        Map<String, Object> data = new HashMap<>();
        data.put("sku", sku);
        data.put("qty", qty);
        return new ConceptRecord("Line", id, "default", data, 1L);
    }

    private static List<String> ids(List<ConceptRecord> rows) {
        return rows.stream().map(ConceptRecord::id).toList();
    }

    private static List<ConceptRecord> shuffled() {
        // store order = id order: zz, mm, bb, aa -- the order the probe saw before the fix
        return new ArrayList<>(List.of(row("zz", "sku-3", 5), row("mm", "sku-2", 20), row("bb", "sku-1", 5), row("aa", "sku-4", null)));
    }

    @Test
    void sortsAscendingByDefaultAndDescendingOnRequest() {
        assertEquals(List.of("bb", "mm", "zz", "aa"), ids(AggregateRuntime.sortedByOrderBy(shuffled(), "sku")));
        assertEquals(List.of("aa", "zz", "mm", "bb"), ids(AggregateRuntime.sortedByOrderBy(shuffled(), "sku DESC")));
    }

    @Test
    void numbersCompareAsNumbersNullsComeLastAndLaterKeysBreakTies() {
        // qty 5 ties between zz and bb; sku breaks it. 20 > 5 numerically (a string compare would say "20" < "5").
        assertEquals(List.of("bb", "zz", "mm", "aa"), ids(AggregateRuntime.sortedByOrderBy(shuffled(), "qty, sku")));
        assertEquals(List.of("mm", "bb", "zz", "aa"), ids(AggregateRuntime.sortedByOrderBy(shuffled(), "qty desc, sku")));
    }

    @Test
    void aBlankOrUnusableSpecLeavesTheStoreOrderUntouched() {
        List<ConceptRecord> rows = shuffled();
        assertSame(rows, AggregateRuntime.sortedByOrderBy(rows, null));
        assertSame(rows, AggregateRuntime.sortedByOrderBy(rows, "  "));
        assertSame(rows, AggregateRuntime.sortedByOrderBy(rows, " , "));
    }
}
