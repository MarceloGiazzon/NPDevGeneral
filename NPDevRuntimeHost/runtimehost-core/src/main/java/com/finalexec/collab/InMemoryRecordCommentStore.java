package com.finalexec.collab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** PROC-5: in-process store for {@code npdev.storage.mode=in-memory} and tests. */
public final class InMemoryRecordCommentStore implements RecordCommentStore {

    private final Map<String, RecordComment> byKey = new ConcurrentHashMap<>();

    private static String key(String tenantId, String commentId) {
        return tenantId + "|" + commentId;
    }

    @Override
    public void insert(RecordComment comment) {
        if (byKey.putIfAbsent(key(comment.tenantId(), comment.id()), comment) != null) {
            throw new IllegalStateException("duplicate comment id " + comment.id());
        }
    }

    @Override
    public List<RecordComment> list(String tenantId, String conceptName, String recordId, int limit) {
        List<RecordComment> rows = new ArrayList<>();
        for (RecordComment row : byKey.values()) {
            if (!row.isDeleted() && row.tenantId().equals(tenantId) && row.conceptName().equals(conceptName)
                    && row.recordId().equals(recordId)) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingLong(RecordComment::createdAtMs).thenComparing(RecordComment::id));
        return rows.size() <= limit ? rows : new ArrayList<>(rows.subList(0, limit));
    }

    @Override
    public Optional<RecordComment> findById(String tenantId, String commentId) {
        return Optional.ofNullable(byKey.get(key(tenantId, commentId)));
    }

    @Override
    public java.util.Set<String> attachmentKeys(String tenantId) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RecordComment row : byKey.values()) {
            if (!row.isDeleted() && row.tenantId().equals(tenantId) && RecordComment.KIND_ATTACHMENT.equals(row.kind())) {
                AttachmentRef.parse(row.fileJson()).ifPresent(ref -> keys.add(ref.key()));
            }
        }
        return keys;
    }

    @Override
    public boolean softDelete(String tenantId, String commentId, long nowMs) {
        boolean[] changed = {false};
        byKey.computeIfPresent(key(tenantId, commentId), (k, row) -> {
            if (row.isDeleted()) {
                return row;
            }
            changed[0] = true;
            return new RecordComment(row.id(), row.tenantId(), row.conceptName(), row.recordId(), row.author(),
                    row.body(), row.kind(), row.fileJson(), row.createdAtMs(), nowMs);
        });
        return changed[0];
    }
}
