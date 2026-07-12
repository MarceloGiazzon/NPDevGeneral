package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * A standalone, reusable modal selector (e.g. WMS "Seleciona Ruas"): a named
 * multi- or single-select picker over a concept, referenced by grids/fields via
 * {@code picker}. Conceptually a prompt surface promoted to a first-class,
 * shareable declaration; the compiler expands it into an ordinary picker panel.
 * See ADR-0005 and docs/architecture/AGGREGATE_WORKBENCH_PLAN.md.
 */
public record SelectorAst(
        String name,
        String concept,
        boolean multiSelect,
        List<String> filters,
        List<String> columns,
        Map<String, Object> returnMapping,
        Map<String, Object> metadata
) {
    public SelectorAst {
        filters = filters == null ? List.of() : List.copyOf(filters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        returnMapping = returnMapping == null ? Map.of() : Map.copyOf(returnMapping);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
