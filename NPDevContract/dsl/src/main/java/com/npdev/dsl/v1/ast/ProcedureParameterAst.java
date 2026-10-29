package com.npdev.dsl.v1.ast;

public record ProcedureParameterAst(
        String name,
        String type,
        boolean required,
        SchemaAst schema,
        String description,
        String defaultExpression
) {
    /** The parameter without a default -- every declaration that predates REG-313 (b). */
    public ProcedureParameterAst(String name, String type, boolean required, SchemaAst schema, String description) {
        this(name, type, required, schema, description, null);
    }
}
