package com.npdev.kernel.concepts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** REG-318(6): a grouped reference key keeps its id and gains a readable {@code <field>Display}. */
class ConceptAggregateReferenceLabelsTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static final List<ConceptAggregateReferenceLabels.Spec> SPECS =
            List.of(new ConceptAggregateReferenceLabels.Spec("site", "Site", "name"));

    @Test
    void addsTheTargetsDisplayFieldBesideTheKeyAndReadsEachTargetOnce() {
        AtomicInteger reads = new AtomicInteger();
        List<Map<String, Object>> rows = ConceptAggregateReferenceLabels.apply(
                List.of(row("site", "id-1", "n", 2L), row("site", "id-2", "n", 1L), row("site", "id-1", "n", 5L)),
                SPECS,
                (concept, id) -> {
                    reads.incrementAndGet();
                    assertEquals("Site", concept);
                    return Map.of("name", "Site " + id);
                });
        assertEquals("id-1", rows.get(0).get("site"));
        assertEquals("Site id-1", rows.get(0).get("siteDisplay"));
        assertEquals("Site id-2", rows.get(1).get("siteDisplay"));
        assertEquals("Site id-1", rows.get(2).get("siteDisplay"));
        assertEquals(2, reads.get());
    }

    @Test
    void anUnreadableOrMissingTargetGivesANullLabelAndANullKeyIsLeftAlone() {
        List<Map<String, Object>> rows = ConceptAggregateReferenceLabels.apply(
                List.of(row("site", "gone", "n", 1L), row("site", null, "n", 1L)), SPECS, (concept, id) -> null);
        assertNull(rows.get(0).get("siteDisplay"));
        assertEquals(true, rows.get(0).containsKey("siteDisplay"));
        assertEquals(false, rows.get(1).containsKey("siteDisplay"));
    }

    @Test
    void anExistingColumnWithThatNameIsNeverOverwritten() {
        List<Map<String, Object>> rows = ConceptAggregateReferenceLabels.apply(
                List.of(row("site", "id-1", "siteDisplay", "mine")), SPECS, (concept, id) -> Map.of("name", "x"));
        assertEquals("mine", rows.get(0).get("siteDisplay"));
    }

    @Test
    void noSpecsReturnsTheRowsUntouched() {
        List<Map<String, Object>> in = List.of(row("a", 1));
        assertEquals(in, ConceptAggregateReferenceLabels.apply(in, List.of(), (c, i) -> null));
    }
}
