package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record ProcedureAst(
        String name,
        String description,
        List<ProcedureParameterAst> parameters,
        List<ProcedureVariableAst> variables,
        List<ProcedureStepAst> steps,
        SchemaAst returns,
        List<String> permissionRequirements,
        String tracePolicy,
        String auditPolicy,
        GeneratedActionDescriptorAst actionDescriptor,
        Map<String, Object> metadata
) {
    public ProcedureAst {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        variables = variables == null ? List.of() : List.copyOf(variables);
        steps = steps == null ? List.of() : List.copyOf(steps);
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
