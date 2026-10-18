package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record PanelLayoutAst(
        String type,
        List<PanelLayoutAst> children,
        List<String> fields,
        Map<String, Object> metadata,
        List<PanelSlotAst> slots,
        List<PanelWidgetAst> widgets
) {
    public PanelLayoutAst {
        children = children == null ? List.of() : List.copyOf(children);
        fields = fields == null ? List.of() : List.copyOf(fields);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        slots = slots == null ? List.of() : List.copyOf(slots);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
    }

    public PanelLayoutAst(String type, List<PanelLayoutAst> children, List<String> fields, Map<String, Object> metadata,
                          List<PanelSlotAst> slots) {
        this(type, children, fields, metadata, slots, List.of());
    }

    public PanelLayoutAst(String type, List<PanelLayoutAst> children, List<String> fields, Map<String, Object> metadata) {
        this(type, children, fields, metadata, List.of(), List.of());
    }
}
