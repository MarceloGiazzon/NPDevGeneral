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
        GeneratedActionDescriptorAst actionDescriptor,
        Map<String, Object> metadata,
        String uid,
        String runAs
) {
    public ProcedureAst {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        variables = variables == null ? List.of() : List.copyOf(variables);
        steps = steps == null ? List.of() : List.copyOf(steps);
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Pre-runAs convenience constructor -- runs as the caller. */
    public ProcedureAst(
            String name,
            String description,
            List<ProcedureParameterAst> parameters,
            List<ProcedureVariableAst> variables,
            List<ProcedureStepAst> steps,
            SchemaAst returns,
            List<String> permissionRequirements,
            GeneratedActionDescriptorAst actionDescriptor,
            Map<String, Object> metadata,
            String uid
    ) {
        this(name, description, parameters, variables, steps, returns, permissionRequirements,
                actionDescriptor, metadata, uid, null);
    }

    /** Pre-P2.1 convenience constructor -- uid defaults to null (no stable identity declared). */
    public ProcedureAst(
            String name,
            String description,
            List<ProcedureParameterAst> parameters,
            List<ProcedureVariableAst> variables,
            List<ProcedureStepAst> steps,
            SchemaAst returns,
            List<String> permissionRequirements,
            GeneratedActionDescriptorAst actionDescriptor,
            Map<String, Object> metadata
    ) {
        this(name, description, parameters, variables, steps, returns, permissionRequirements,
                actionDescriptor, metadata, null, null);
    }
}
