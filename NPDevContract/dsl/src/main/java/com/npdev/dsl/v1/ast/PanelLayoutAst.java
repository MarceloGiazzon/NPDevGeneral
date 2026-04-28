package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record PanelLayoutAst(
        String type,
        List<PanelLayoutAst> children,
        List<String> fields,
        Map<String, Object> metadata
) {
    public PanelLayoutAst {
        children = children == null ? List.of() : List.copyOf(children);
        fields = fields == null ? List.of() : List.copyOf(fields);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
