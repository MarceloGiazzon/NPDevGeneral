package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record PanelDataSourceAst(
        String name,
        String concept,
        String query,
        String procedure,
        Map<String, Object> params,
        String parentDataSource,
        String parentField,
        String childField,
        List<String> rowOps,
        List<String> addFormFields,
        String onRowLoad
) {
    public PanelDataSourceAst {
        params = params == null ? Map.of() : Map.copyOf(params);
        rowOps = rowOps == null ? List.of() : List.copyOf(rowOps);
        addFormFields = addFormFields == null ? List.of() : List.copyOf(addFormFields);
    }
}
