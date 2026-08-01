package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * Optional per-surface overrides for an {@link AutoPanelAst}. Any unset value is
 * derived from the bound concept during expansion (see ADR-0005). Not every field
 * is meaningful for every surface (e.g. {@code labelField} is a Prompt concern);
 * the expander reads what it needs and ignores the rest.
 */
public record AutoPanelSurfaceAst(
        List<String> filters,
        List<String> columns,
        List<String> fields,
        List<AutoPanelComputedAst> computed,
        String labelField,
        Map<String, Object> metadata,
        TransactionHooksAst hooks,
        List<DerivedFieldAst> derivedFields,
        Map<String, RegionMountAst> regions,
        List<WorkbenchActionAst> actions,
        Map<String, String> visibleWhen,
        Map<String, WorkbenchBandPickerAst> bandPickers,
        AutoPanelDataSourceAst dataSource,
        /** Move 11 W6: declared transient UI state a {@code $ui.<name>} visibleWhen predicate reads. */
        Map<String, UiStateControlAst> uiState
) {
    public AutoPanelSurfaceAst {
        filters = filters == null ? List.of() : List.copyOf(filters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        fields = fields == null ? List.of() : List.copyOf(fields);
        computed = computed == null ? List.of() : List.copyOf(computed);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        derivedFields = derivedFields == null ? List.of() : List.copyOf(derivedFields);
        regions = regions == null ? Map.of() : Map.copyOf(regions);
        actions = actions == null ? List.of() : List.copyOf(actions);
        visibleWhen = visibleWhen == null ? Map.of() : Map.copyOf(visibleWhen);
        bandPickers = bandPickers == null ? Map.of() : Map.copyOf(bandPickers);
        uiState = uiState == null ? Map.of() : Map.copyOf(uiState);
    }
}
