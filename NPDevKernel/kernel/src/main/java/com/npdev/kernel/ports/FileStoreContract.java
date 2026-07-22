package com.npdev.kernel.ports;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;

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

    /**
     * HARDEN-DL-P1: resolves the authoritative {@link FileHandle} (contentType/originalName/size)
     * for {@code key} as recorded at upload time -- callers must never trust a caller-supplied
     * content-type when serving bytes back, since that is half of a stored-XSS primitive. Throws
     * {@link java.util.NoSuchElementException} if no metadata is stored for the key.
     */
    FileHandle head(String storeId, String key);

    /** Idempotent: deleting an already-deleted or unknown handle is not an error. */
    void delete(FileHandle handle);

    /** True if {@code handle}'s bytes are currently stored (used by orphan-cleanup sweeps). */
    boolean exists(FileHandle handle);

    /** HARDEN-GC-P3: a stored object's key and upload time, for orphan-sweep enumeration. */
    record StoredObject(String key, Instant uploadedAt) {
    }

    /**
     * HARDEN-GC-P3: every tenant segment with at least one stored object -- the orphan sweep's
     * starting point, since it must scan per tenant (keys are only ever tenant-scoped).
     */
    List<String> listTenants();

    /** HARDEN-GC-P3: every object currently stored for {@code tenantId}, for the orphan sweep. */
    List<StoredObject> list(String tenantId);
}
