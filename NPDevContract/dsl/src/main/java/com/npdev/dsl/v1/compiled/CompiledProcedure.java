package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledProcedure(
        String name,
        String description,
        List<CompiledProcedureParameter> parameters,
        List<CompiledProcedureVariable> variables,
        List<CompiledProcedureStep> steps,
        CompiledSchema returns,
        List<String> permissionRequirements,
        String tracePolicy,
        CompiledGeneratedActionDescriptorSpec actionDescriptor,
        Map<String, Object> metadata
) {
    public CompiledProcedure(
            String name,
            String description,
            List<CompiledProcedureParameter> parameters,
            List<CompiledProcedureVariable> variables,
            List<CompiledProcedureStep> steps,
            CompiledSchema returns,
            List<String> permissionRequirements,
            String tracePolicy,
            Map<String, Object> metadata
    ) {
        this(
                name,
                description,
                parameters,
                variables,
                steps,
                returns,
                permissionRequirements,
                tracePolicy,
                null,
                metadata
        );
    }

    public CompiledProcedure {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        variables = variables == null ? List.of() : List.copyOf(variables);
        steps = steps == null ? List.of() : List.copyOf(steps);
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
