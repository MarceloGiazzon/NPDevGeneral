package com.npdev.dsl.v1.ast;

import java.util.Map;

public record PanelDataSourceAst(
        String name,
        String concept,
        String query,
        String procedure,
        Map<String, Object> params
) {
    public PanelDataSourceAst {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
