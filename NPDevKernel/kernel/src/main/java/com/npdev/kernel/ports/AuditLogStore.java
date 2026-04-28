package com.npdev.kernel.ports;

import com.npdev.kernel.audit.AuditRecord;

import java.util.List;

public interface AuditLogStore {
    void append(AuditRecord record);

    List<AuditRecord> search(AuditQuery query);

    static AuditLogStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final AuditLogStore INSTANCE = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };

        private NoopHolder() {
        }
    }
}

