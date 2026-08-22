package com.npdev.dsl.v1.compiled;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiled per-surface overrides for a {@link CompiledAutoPanel}. See {@link com.npdev.dsl.v1.ast.AutoPanelSurfaceAst}. */
public record CompiledAutoPanelSurface(
        List<String> filters,
        List<String> columns,
        List<String> fields,
        List<CompiledAutoPanelComputed> computed,
        String labelField,
        Map<String, Object> metadata,
        CompiledTransactionHooks hooks,
        List<CompiledDerivedField> derivedFields,
        Map<String, CompiledRegionMount> regions,
        List<CompiledWorkbenchAction> actions,
        Map<String, String> visibleWhen,
        Map<String, CompiledWorkbenchBandPicker> bandPickers,
        CompiledAutoPanelDataSource dataSource,
        /** Move 11 W6: declared transient UI state a {@code $ui.<name>} visibleWhen predicate reads. */
        Map<String, CompiledUiStateControl> uiState
) {
    public CompiledAutoPanelSurface {
        filters = filters == null ? List.of() : List.copyOf(filters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        fields = fields == null ? List.of() : List.copyOf(fields);
        computed = computed == null ? List.of() : List.copyOf(computed);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        derivedFields = derivedFields == null ? List.of() : List.copyOf(derivedFields);
        // REG-175/REG-146: CompiledModelCanonicalJson's toRegions/toVisibleWhen/toBandPickers/
        // toUiState entrySet()-iterate these straight into compiled-model.json with no sort (unlike
        // this class's own `metadata` field, which is routed through a sorting helper first) --
        // Map.copyOf's JEP 269 iteration-order randomization was reaching emitted output.
        regions = regions == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(regions));
        actions = actions == null ? List.of() : List.copyOf(actions);
        visibleWhen = visibleWhen == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(visibleWhen));
        bandPickers = bandPickers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(bandPickers));
        uiState = uiState == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(uiState));
    }
}
