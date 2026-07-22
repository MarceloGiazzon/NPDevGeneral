package com.npdev.dsl.v1.compiled;

import java.util.Map;

/** REG-12 Slice 3: compiled form of {@link com.npdev.dsl.v1.ast.DocumentAst}. */
public record CompiledDocument(
        String name,
        String concept,
        String title,
        String pageSize,
        Double marginMm,
        Map<String, Object> metadata
) {
    public CompiledDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
