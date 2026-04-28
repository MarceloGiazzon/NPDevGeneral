package com.npdev.dsl.v1.ast;

public record ProcedureParameterAst(
        String name,
        String type,
        boolean required,
        SchemaAst schema,
        String description
) {
}
