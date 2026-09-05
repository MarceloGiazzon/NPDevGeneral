package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A standalone, reusable modal selector (e.g. WMS "Seleciona Ruas"): a named
 * multi- or single-select picker over a concept, referenced by grids/fields via
 * {@code picker}. Conceptually a prompt surface promoted to a first-class,
 * shareable declaration; the compiler expands it into an ordinary picker panel.
 * See ADR-0005 and docs/architecture/AGGREGATE_WORKBENCH_PLAN.md.
 *
 * <p>REAL_LIFT_PLAN_2026-09-03 package C2 (boundary B16 Step 2, EDIT-18): {@code
 * field.picker.selectorRef}/{@code transaction.bandPickers.<name>.selectorRef} finally wire the
 * "referenced by grids/fields via picker" claim above -- true for the first time. {@code filters}
 * (searchable field NAMES) and {@code columns} (display field names) map onto that reference's
 * search[]/display[] directly; {@code orderBy} (display field names, sort order) and {@code filter}
 * (a real {@link com.npdev.dsl.v1.query.PickerFilterGrammar} predicate, distinct from the
 * search-field-name {@code filters} list above) are the two properties that were missing.
 */
public record SelectorAst(
        String name,
        String concept,
        boolean multiSelect,
        List<String> filters,
        List<String> columns,
        Map<String, Object> returnMapping,
        Map<String, Object> metadata,
        List<String> orderBy,
        String filter
) {
    public SelectorAst {
        filters = filters == null ? List.of() : List.copyOf(filters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        // REG-175/REG-146: AutoPanelExpander.expandSelector nests this straight into
        // CompiledPanel.metadata["returnMapping"] -- the outer metadata map's own keys get sorted
        // before JSON serialization, but nested map VALUES are not, so this map's own iteration
        // order (randomized by Map.copyOf's JEP 269 mitigation) was reaching compiled-model.json.
        returnMapping = returnMapping == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(returnMapping));
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
