package com.npdev.dsl.v1.compiled;

/**
 * R5.3: compiled form of {@link com.npdev.dsl.v1.ast.SequenceAst} -- see that class's javadoc for
 * the full design rationale (why {@code name} is deliberately never pack-qualified).
 */
public record CompiledSequence(String name, String format, String scope) {
    public CompiledSequence {
        scope = (scope == null || scope.isBlank()) ? "global" : scope.toLowerCase(java.util.Locale.ROOT);
    }
}
