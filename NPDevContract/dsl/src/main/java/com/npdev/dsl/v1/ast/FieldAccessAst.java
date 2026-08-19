package com.npdev.dsl.v1.ast;

/**
 * R5.5 (roadmap Wave 1, {@code field.access: { read, write }}): an author-declared field-level
 * authorization rule, the next rung on the same ladder {@link ConceptAccessAst} (row scope)
 * already provides -- role ceiling (permission) -> row scope (concept.access) -> field scope
 * (this). Each expression is evaluated per-record through the platform's unified expression
 * language ({@code ComputedExpression}), with {@code $user.*} pseudo-fields available alongside
 * the record's own fields, exactly like {@link ConceptAccessAst}. {@code read} gates whether this
 * field's value may appear in a read/list/query response for a given record (denied -- the field
 * key is OMITTED from the response entirely, never returned masked/null, so a denial never
 * confirms whether a value is present); {@code write} gates whether a save may set/change this
 * field's value (denied -- the WHOLE write is rejected, never silently dropped, so the caller
 * cannot be misled into believing a partial write succeeded).
 */
public final class FieldAccessAst {
    private final String read;
    private final String write;

    public FieldAccessAst(String read, String write) {
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
