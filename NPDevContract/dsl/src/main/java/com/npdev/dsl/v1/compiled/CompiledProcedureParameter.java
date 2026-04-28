package com.npdev.dsl.v1.compiled;

public record CompiledProcedureParameter(
        String name,
        String type,
        boolean required,
        CompiledSchema schema,
        String description
) {
}
