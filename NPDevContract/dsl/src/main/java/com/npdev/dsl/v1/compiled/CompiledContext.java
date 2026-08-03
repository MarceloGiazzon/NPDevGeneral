package com.npdev.dsl.v1.compiled;

/**
 * B20 (S2): compiled form of {@link com.npdev.dsl.v1.ast.ContextAst} -- a declared bounded context's
 * name and the relative {@code $ref} it was composed from. Carried through for introspection (which
 * contexts this app declares, and where they came from); the qualification itself
 * ({@code contextName::Member}) already lives on every member this context contributed, exactly
 * parallel to how a pack import's {@code packId::Member} qualification works.
 */
public record CompiledContext(String name, String ref) {
}
