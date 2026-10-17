package com.finalexec.collab;

/**
 * PROC-5/PROC-6: one row of a record's thread -- a plain-text comment ({@link #KIND_COMMENT}) or, from
 * PROC-6, an attachment ({@link #KIND_ATTACHMENT}) whose file reference is {@code fileJson}.
 * {@code deletedAtMs} is null while the row is live.
 */
public record RecordComment(
        String id,
        String tenantId,
        String conceptName,
        String recordId,
        String author,
        String body,
        String kind,
        String fileJson,
        long createdAtMs,
        Long deletedAtMs
) {
    public static final String KIND_COMMENT = "COMMENT";
    public static final String KIND_ATTACHMENT = "ATTACHMENT";

    public boolean isDeleted() {
        return deletedAtMs != null;
    }
}
