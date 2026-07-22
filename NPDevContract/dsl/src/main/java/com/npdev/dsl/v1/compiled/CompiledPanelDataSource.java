package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledPanelDataSource(
        String name,
        String concept,
        String query,
        String procedure,
        Map<String, Object> params,
        String parentDataSource,
        String parentField,
        String childField,
        List<String> rowOps,
        List<String> addFormFields
) {
    public CompiledPanelDataSource {
        params = params == null ? Map.of() : Map.copyOf(params);
        rowOps = rowOps == null ? List.of() : List.copyOf(rowOps);
        addFormFields = addFormFields == null ? List.of() : List.copyOf(addFormFields);
    }

    /** LIFT-ROWOPS-P1: back-compat 8-arg constructor for existing callers without rowOps. */
    public CompiledPanelDataSource(
            String name,
            String concept,
            String query,
            String procedure,
            Map<String, Object> params,
            String parentDataSource,
            String parentField,
            String childField
    ) {
        this(name, concept, query, procedure, params, parentDataSource, parentField, childField, List.of(), List.of());
    }

    public boolean supportsAdd() {
        return rowOps.stream().anyMatch("add"::equalsIgnoreCase);
    }

    public boolean supportsDelete() {
        return rowOps.stream().anyMatch("delete"::equalsIgnoreCase);
    }
}
