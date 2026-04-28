package com.npdev.kernel.ports;

import com.npdev.kernel.trace.TraceSummary;

import java.util.List;

public interface TraceSummaryStore {
    List<TraceSummary> searchSummaries(TraceQuery query);

    static TraceSummaryStore noop() {
        return query -> List.of();
    }
}

