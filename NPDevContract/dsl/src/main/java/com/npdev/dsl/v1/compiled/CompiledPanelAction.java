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
        List<String> inputFields,
        String resultAs,
        String filename,
        String contentType
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
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 4 / Gap 7): resultAs="download" makes
        // PanelRuntime.executeAction return a download descriptor (filename/contentType/raw content)
        // instead of a JSON result, which RuntimeUiMetadataController streams as a real file response
        // -- inventario.html's Gerar Template ("produce a file, stream it, persist nothing") had no
        // declared surface for this before; the checklist's own note is "never <a download>".
        resultAs = resultAs == null || resultAs.isBlank() ? null : resultAs.trim();
        filename = filename == null || filename.isBlank() ? null : filename.trim();
        contentType = contentType == null || contentType.isBlank() ? null : contentType.trim();
    }
}
