package com.npdev.dsl.v1.compiled;

/**
 * LNCH-13: a compiled row-level (data-scoped) authorization rule on a concept (from
 * {@code access: { read, write }}). {@code read} scopes which rows a query/list may return;
 * {@code write} scopes which rows a save/delete may affect. Either may be absent. P6:
 * {@code publicRead} is the separate anonymous grant ({@code access.public}), null when undeclared.
 */
public final class CompiledConceptAccess {
    private final String read;
    private final String write;
    private final CompiledPublicRead publicRead;

    public CompiledConceptAccess(String read, String write) {
        this(read, write, null);
    }

    public CompiledConceptAccess(String read, String write, CompiledPublicRead publicRead) {
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

    public CompiledPublicRead getPublicRead() {
        return publicRead;
    }
}
