package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledPanel(
        String name,
        String route,
        String title,
        List<CompiledPanelDataSource> dataSources,
        CompiledPanelLayout layout,
        List<CompiledPanelFieldBinding> fieldBindings,
        String visibility,
        String enabledWhen,
        List<CompiledPanelAction> actions,
        Map<String, Object> explainability,
        Map<String, Object> metadata
) {
    public CompiledPanel {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        fieldBindings = fieldBindings == null ? List.of() : List.copyOf(fieldBindings);
        actions = actions == null ? List.of() : List.copyOf(actions);
        explainability = explainability == null ? Map.of() : Map.copyOf(explainability);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
