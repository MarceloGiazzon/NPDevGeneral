package com.npdev.dsl.v1.compiled;

public record CompiledProcedureVariable(
        String name,
        String type,
        CompiledSchema schema,
        Object initialValue
) {
}
