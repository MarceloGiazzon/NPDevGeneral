package com.npdev.dsl.v1.ast;

/**
 * LNCH-13: an author-declared row-level (data-scoped) authorization rule on a concept
 * ({@code access: { read, write }}). Each expression is evaluated per-record through the
 * platform's unified expression language ({@code ComputedExpression}), with {@code $user.*}
 * pseudo-fields available alongside the record's own fields. P6: {@code access.public} is the
 * separate anonymous read grant -- see {@link PublicReadAst}.
 */
public final class ConceptAccessAst {
    private final String read;
    private final String write;
    private final PublicReadAst publicRead;

    public ConceptAccessAst(String read, String write) {
        this(read, write, null);
    }

    public ConceptAccessAst(String read, String write, PublicReadAst publicRead) {
        this.read = (read == null || read.isBlank()) ? null : read.trim();
        this.write = (write == null || write.isBlank()) ? null : write.trim();
        this.publicRead = publicRead;
    }

    public String getRead() {
        return read;
    }

    public String getWrite() {
        return write;
    }

    public PublicReadAst getPublicRead() {
        return publicRead;
    }
}
