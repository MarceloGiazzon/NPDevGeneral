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
 *
 * <p>{@code physicallyIsolate} (S8 Wave 4, ADR-0011 D4's own named v2 escape): opts this context's
 * concepts into a context-qualified, mangled table name ({@code SqlIdentifierSupport}'s existing
 * {@code "::"->"_"} replacement) instead of D4 v1's default (the context qualifier is invisible to
 * the physical schema). Defaults {@code false} -- absent from the source model, this behaves exactly
 * as before Wave 4.
 */
public record ContextAst(String name, String ref, boolean physicallyIsolate) {
    public ContextAst {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Context name must be non-blank");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Context $ref must be non-blank");
        }
    }
}
