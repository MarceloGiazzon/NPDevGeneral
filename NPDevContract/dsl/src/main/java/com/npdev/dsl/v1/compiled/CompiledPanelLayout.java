package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledPanelLayout(
        String type,
        List<CompiledPanelLayout> children,
        List<String> fields,
        Map<String, Object> metadata,
        List<CompiledPanelSlot> slots,
        List<CompiledPanelWidget> widgets
) {
    public CompiledPanelLayout {
        children = children == null ? List.of() : List.copyOf(children);
        fields = fields == null ? List.of() : List.copyOf(fields);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        slots = slots == null ? List.of() : List.copyOf(slots);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
    }

    public CompiledPanelLayout(String type, List<CompiledPanelLayout> children, List<String> fields, Map<String, Object> metadata,
                               List<CompiledPanelSlot> slots) {
        this(type, children, fields, metadata, slots, List.of());
    }

    public CompiledPanelLayout(String type, List<CompiledPanelLayout> children, List<String> fields, Map<String, Object> metadata) {
        this(type, children, fields, metadata, List.of(), List.of());
    }
}
