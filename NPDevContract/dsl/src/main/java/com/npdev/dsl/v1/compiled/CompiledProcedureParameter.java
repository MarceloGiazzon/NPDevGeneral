package com.npdev.dsl.v1.compiled;

/**
 * @param defaultExpression REG-313 (b): for a QUERY parameter, an expression (literals and the registered value
 *                          functions such as {@code addDays(today(), -7)}; no field references) the runtime
 *                          evaluates when the caller does not supply the parameter; null when it declares none
 */
public record CompiledProcedureParameter(
        String name,
        String type,
        boolean required,
        CompiledSchema schema,
        String description,
        String defaultExpression
) {
    public CompiledProcedureParameter(
            String name, String type, boolean required, CompiledSchema schema, String description) {
        this(name, type, required, schema, description, null);
    }
}
