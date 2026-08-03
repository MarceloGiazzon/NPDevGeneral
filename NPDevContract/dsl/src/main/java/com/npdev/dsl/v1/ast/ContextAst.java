package com.npdev.dsl.v1.ast;

/**
 * B20 (S2, docs/adr/ADR-0011-bounded-contexts.md): a declared bounded context -- {@code name} plus
 * the relative {@code $ref} to the fragment file (pack-shaped, composed by
 * {@code ModelSourceResolver} the same way {@code packs}/{@code fragments} already are) whose
 * concepts/queries/panels/flows belong to that context. By the time this survives parsing, the
 * fragment has ALREADY been composed and its members qualified {@code contextName::Member} (D1),
 * exactly parallel to how a pack import qualifies its members {@code packId::Member} -- this record
 * is metadata (which contexts exist, and where they came from), not a container for the qualified
 * members themselves.
 */
public record ContextAst(String name, String ref) {
    public ContextAst {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Context name must be non-blank");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Context $ref must be non-blank");
        }
    }
}
