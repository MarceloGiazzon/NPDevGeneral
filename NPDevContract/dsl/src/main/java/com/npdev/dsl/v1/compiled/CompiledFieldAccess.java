package com.npdev.dsl.v1.compiled;

/**
 * R5.5: compiled form of a field-level authorization rule (from {@code field.access:
 * { read, write }}). {@code read} scopes whether this field's value is visible in a read/list/
 * query response for a given record; {@code write} scopes whether a save may set/change this
 * field's value for a given record. Either may be absent. See
 * {@link com.npdev.dsl.v1.ast.FieldAccessAst} for the full contract this compiles from.
 */
public final class CompiledFieldAccess {
    private final String read;
    private final String write;

    public CompiledFieldAccess(String read, String write) {
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
