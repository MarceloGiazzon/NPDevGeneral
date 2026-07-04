package com.npdev.dsl.v1.compiled;

import java.util.Map;

public record CompiledPanelDataSource(
        String name,
        String concept,
        String query,
        String procedure,
        Map<String, Object> params,
        String parentDataSource,
        String parentField,
        String childField
) {
    public CompiledPanelDataSource {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
