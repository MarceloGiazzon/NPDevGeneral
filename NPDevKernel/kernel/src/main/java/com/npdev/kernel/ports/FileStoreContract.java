package com.npdev.kernel.ports;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * LIFT-UPLOAD-P1: adapter-neutral file storage port, mirroring the platform's existing
 * {@code *-inproc}/{@code *-postgres} adapter-pair pattern. Bytes never go through the primary DB
 * (the {@link ConceptStore}/{@code ConceptGateway} path) -- a record holds only a
 * {@link FileHandle}, and reads/writes of the actual bytes go through this port instead.
 */
public interface FileStoreContract {

    /**
     * Stores {@code content} (fully consumed, not retained) under a key scoped to {@code tenantId},
     * returning the handle to persist on the owning record.
     */
    FileHandle put(
            String tenantId,
            String originalName,
            String contentType,
            long sizeBytes,
            InputStream content
    );

    /** Streams the stored bytes for {@code handle} into {@code destination} (caller closes it). */
    void get(FileHandle handle, OutputStream destination);

    /** Idempotent: deleting an already-deleted or unknown handle is not an error. */
    void delete(FileHandle handle);

    /** True if {@code handle}'s bytes are currently stored (used by orphan-cleanup sweeps). */
    boolean exists(FileHandle handle);
}
