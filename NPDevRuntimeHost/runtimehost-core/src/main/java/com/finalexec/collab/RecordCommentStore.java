package com.finalexec.collab;

import java.util.List;
import java.util.Optional;

/**
 * PROC-5/PROC-6: persistence of record threads. Every method is tenant-scoped; a thread is the rows
 * sharing (tenant, concept, record). Deleting is a soft delete: the row stays, stamped.
 */
public interface RecordCommentStore {

    void insert(RecordComment comment);

    /** Live (not deleted) rows of the thread, oldest first, at most {@code limit}. */
    List<RecordComment> list(String tenantId, String conceptName, String recordId, int limit);

    /** The row whether or not it was deleted -- the caller decides what a deleted row means. */
    Optional<RecordComment> findById(String tenantId, String commentId);

    /** Stamps {@code deleted_at_ms}; returns false when no live row matched. */
    boolean softDelete(String tenantId, String commentId, long nowMs);
}
