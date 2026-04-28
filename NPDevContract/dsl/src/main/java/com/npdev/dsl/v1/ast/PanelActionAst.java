package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record PanelActionAst(
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
        Map<String, Object> metadata
) {
    public PanelActionAst {
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        explainability = explainability == null ? Map.of() : Map.copyOf(explainability);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
