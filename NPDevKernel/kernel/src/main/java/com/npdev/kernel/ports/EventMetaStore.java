package com.npdev.kernel.ports;

import com.npdev.kernel.events.EventMetaSummary;

import java.util.List;

public interface EventMetaStore {
    List<EventMetaSummary> listByCorrelation(String tenantId, String correlationId, int limit, int offset);

    static EventMetaStore noop() {
        return (tenantId, correlationId, limit, offset) -> List.of();
    }
}

