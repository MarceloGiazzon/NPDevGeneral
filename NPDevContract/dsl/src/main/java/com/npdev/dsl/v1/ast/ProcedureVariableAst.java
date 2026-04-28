package com.npdev.dsl.v1.ast;

public record ProcedureVariableAst(
        String name,
        String type,
        SchemaAst schema,
        Object initialValue
) {
}
