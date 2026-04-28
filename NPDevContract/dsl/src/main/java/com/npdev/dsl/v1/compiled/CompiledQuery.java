package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledQuery(
        String name,
        String concept,
        String where,
        List<String> orderBy,
        Integer limit,
        List<CompiledProcedureParameter> parameters,
        List<String> permissionRequirements,
        String tracePolicy,
        String auditPolicy,
        Map<String, Object> metadata
) {
    public CompiledQuery {
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
