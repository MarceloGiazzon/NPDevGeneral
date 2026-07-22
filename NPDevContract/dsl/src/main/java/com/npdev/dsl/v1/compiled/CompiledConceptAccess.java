package com.npdev.dsl.v1.compiled;

/**
 * LNCH-13: a compiled row-level (data-scoped) authorization rule on a concept (from
 * {@code access: { read, write }}). {@code read} scopes which rows a query/list may return;
 * {@code write} scopes which rows a save/delete may affect. Either may be absent.
 */
public final class CompiledConceptAccess {
    private final String read;
    private final String write;

    public CompiledConceptAccess(String read, String write) {
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
