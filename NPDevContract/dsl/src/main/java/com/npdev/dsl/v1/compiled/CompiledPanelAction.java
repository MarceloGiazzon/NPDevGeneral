package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledPanelAction(
        String name,
        String label,
        String binding,
        String concept,
        String operation,
        String procedure,
        String flow,
        String visibleWhen,
        String enabledWhen,
        List<String> permissionRequirements,
        Map<String, Object> explainability,
        Map<String, Object> metadata,
        String scope,
        String dataSource,
        List<String> inputFields
) {
    public CompiledPanelAction {
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        explainability = explainability == null ? Map.of() : Map.copyOf(explainability);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        // G2 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): default is "panel" so every action declared before
        // this field existed keeps rendering as one panel-header button, unchanged -- no codemod.
        scope = (scope == null || scope.isBlank()) ? "panel" : scope;
        // G3 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): same shape as panelDataSource.addFormFields --
        // field names collected from the user before invoking this action.
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
    }
}
