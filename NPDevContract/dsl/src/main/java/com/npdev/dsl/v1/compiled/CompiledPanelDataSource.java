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
        List<String> addFormFields,
        /** Move 6 Move C (docs/MOVE6_TYPED_SURFACE_PLAN.md §4): a procedure that ENRICHES the rows
         * this data source produced (adds fields; never reorders/adds/drops rows -- a count or id
         * mismatch is a hard runtime failure, not silent truncation). Distinct from {@code
         * procedure} above, which REPLACES the row source entirely. Null if undeclared. */
        String onRowLoad
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
        this(name, concept, query, procedure, params, parentDataSource, parentField, childField, List.of(), List.of(), null);
    }

    /** Move 6 Move C: back-compat 10-arg constructor for existing callers without onRowLoad. */
    public CompiledPanelDataSource(
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
        this(name, concept, query, procedure, params, parentDataSource, parentField, childField, rowOps, addFormFields, null);
    }

    public boolean supportsAdd() {
        return rowOps.stream().anyMatch("add"::equalsIgnoreCase);
    }

    public boolean supportsDelete() {
        return rowOps.stream().anyMatch("delete"::equalsIgnoreCase);
    }
}
