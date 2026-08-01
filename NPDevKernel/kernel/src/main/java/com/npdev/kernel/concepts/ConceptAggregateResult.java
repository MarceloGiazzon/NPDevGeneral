package com.npdev.kernel.concepts;

import java.util.List;
import java.util.Map;

/**
 * Move 10 B1 (LC-B1): the result of a {@link ConceptAggregateQuery} -- rows of aggregate output
 * (groupBy field values keyed by their own field/bucket-label name, aggregate values keyed by their
 * own {@code outputName}), NOT concept records. A Panel/gadget data-source binding needs to know it
 * got this shape, not a {@link ConceptPage} -- see {@code CompiledQuery#isAggregate()}.
 */
public record ConceptAggregateResult(List<Map<String, Object>> rows) {
    public ConceptAggregateResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
