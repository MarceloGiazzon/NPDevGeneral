package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record PanelAst(
        String name,
        String route,
        String title,
        List<PanelDataSourceAst> dataSources,
        PanelLayoutAst layout,
        List<PanelFieldBindingAst> fieldBindings,
        String visibility,
        String enabledWhen,
        List<PanelActionAst> actions,
        Map<String, Object> explainability,
        Map<String, Object> metadata,
        String guidePage,
        OriginAst origin
) {
    public PanelAst {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        fieldBindings = fieldBindings == null ? List.of() : List.copyOf(fieldBindings);
        actions = actions == null ? List.of() : List.copyOf(actions);
        explainability = explainability == null ? Map.of() : Map.copyOf(explainability);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Pre-PACK-2 convenience constructor -- origin defaults to null (not pack-contributed). */
    public PanelAst(
            String name,
            String route,
            String title,
            List<PanelDataSourceAst> dataSources,
            PanelLayoutAst layout,
            List<PanelFieldBindingAst> fieldBindings,
            String visibility,
            String enabledWhen,
            List<PanelActionAst> actions,
            Map<String, Object> explainability,
            Map<String, Object> metadata,
            String guidePage
    ) {
        this(name, route, title, dataSources, layout, fieldBindings, visibility, enabledWhen, actions,
                explainability, metadata, guidePage, null);
    }
}
