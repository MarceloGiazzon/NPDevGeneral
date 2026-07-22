package com.npdev.kernel.ports;

/**
 * LIFT-UPLOAD-P1: an opaque reference to bytes held by a {@link FileStoreContract} adapter --
 * never the bytes themselves. This is what gets persisted on a {@code file}-typed field; the
 * primary DB never holds a blob column (the locked design decision behind this feature).
 */
public record FileHandle(
        String storeId,
        String key,
        String contentType,
        long sizeBytes,
        String originalName
) {
    public FileHandle {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("storeId must be non-blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must be non-blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
