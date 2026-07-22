package com.npdev.dsl.v1.ast;

/**
 * LNCH-13: an author-declared row-level (data-scoped) authorization rule on a concept
 * ({@code access: { read, write }}). Each expression is evaluated per-record through the
 * platform's unified expression language ({@code ComputedExpression}), with {@code $user.*}
 * pseudo-fields available alongside the record's own fields.
 */
public final class ConceptAccessAst {
    private final String read;
    private final String write;

    public ConceptAccessAst(String read, String write) {
        this.read = (read == null || read.isBlank()) ? null : read.trim();
        this.write = (write == null || write.isBlank()) ? null : write.trim();
    }

    public String getRead() {
        return read;
    }

    public String getWrite() {
        return write;
    }
}
