package com.npdev.adapters.audit.inproc;

import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InProcAuditLogStore implements AuditLogStore {
    private static final int DEFAULT_MAX_RECORDS = 10_000;
    private static final Comparator<AuditRecord> DESC_ORDER =
            Comparator.comparingLong(AuditRecord::timestampMs).reversed()
                    .thenComparing(AuditRecord::auditId, Comparator.reverseOrder());

    private final int maxRecords;
    private final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();

    public InProcAuditLogStore() {
        this(DEFAULT_MAX_RECORDS);
    }

    public InProcAuditLogStore(int maxRecords) {
        this.maxRecords = maxRecords <= 0 ? DEFAULT_MAX_RECORDS : maxRecords;
    }

    @Override
    public void append(AuditRecord record) {
        Objects.requireNonNull(record, "record");
        records.add(record);
        int overflow = records.size() - maxRecords;
        if (overflow > 0) {
            for (int i = 0; i < overflow; i++) {
                if (!records.isEmpty()) {
                    records.remove(0);
                }
            }
        }
    }

    @Override
    public List<AuditRecord> search(AuditQuery query) {
        AuditQuery effective = query == null ? AuditQuery.emptyForTenant(null) : query;
        String tenantId = normalize(effective.tenantId());
        if (tenantId == null) {
            return List.of();
        }
        List<AuditRecord> filtered = new ArrayList<>();
        for (AuditRecord record : records) {
            if (!matches(record, effective, tenantId)) {
                continue;
            }
            filtered.add(record);
        }
        filtered.sort(DESC_ORDER);
        return paginate(filtered, effective.limit(), effective.offset());
    }

    private static boolean matches(AuditRecord record, AuditQuery query, String tenantId) {
        if (record == null) {
            return false;
        }
        if (!tenantId.equals(normalize(record.tenantId()))) {
            return false;
        }
        String actorId = normalize(query.actorId());
        if (actorId != null && !actorId.equals(normalize(record.actorId()))) {
            return false;
        }
        String action = normalize(query.action());
        if (action != null && !action.equals(normalize(record.action()))) {
            return false;
        }
        String resourceType = normalize(query.resourceType());
        if (resourceType != null && !resourceType.equals(normalize(record.resourceType()))) {
            return false;
        }
        String resourceId = normalize(query.resourceId());
        if (resourceId != null && !resourceId.equals(normalize(record.resourceId()))) {
            return false;
        }
        Long fromMs = query.fromMs();
        if (fromMs != null && record.timestampMs() < fromMs) {
            return false;
        }
        Long toMs = query.toMs();
        if (toMs != null && record.timestampMs() > toMs) {
            return false;
        }
        return true;
    }

    private static List<AuditRecord> paginate(List<AuditRecord> source, int limit, int offset) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        int effectiveOffset = Math.max(offset, 0);
        int fromIndex = Math.min(effectiveOffset, source.size());
        int toIndex = Math.min(fromIndex + effectiveLimit, source.size());
        return List.copyOf(source.subList(fromIndex, toIndex));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}

