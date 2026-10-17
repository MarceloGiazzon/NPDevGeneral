package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.LARGE_TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

/**
 * PROC-5/PROC-6: the comment thread of a record -- one row per comment (and, PROC-6, per attachment) on a
 * (concept, record) pair. Instants are epoch milliseconds (as {@code npdev_audit_log.ts_ms}); a comment is
 * soft-deleted by stamping {@code deleted_at_ms}, never removed, so the thread's history stays auditable.
 */
public final class NpdevRecordCommentTable {
    public static final String NAME = "npdev_record_comment";

    private NpdevRecordCommentTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("comment_id", TEXT),
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("concept_name", TEXT),
                        InternalColumnDefinition.required("record_id", TEXT),
                        InternalColumnDefinition.required("author", TEXT),
                        InternalColumnDefinition.required("body", LARGE_TEXT),
                        InternalColumnDefinition.required("kind", TEXT),
                        InternalColumnDefinition.optional("file_json", JSON_DOCUMENT),
                        InternalColumnDefinition.required("created_at_ms", BIGINT),
                        InternalColumnDefinition.optional("deleted_at_ms", BIGINT)
                ),
                InternalPrimaryKeyDefinition.of("comment_id"),
                List.of(
                        InternalIndexDefinition.index("idx_record_comment_record",
                                "tenant_id", "concept_name", "record_id", "created_at_ms")
                )
        );
    }
}
