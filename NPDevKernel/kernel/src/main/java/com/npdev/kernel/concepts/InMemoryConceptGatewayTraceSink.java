package com.npdev.kernel.concepts;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryConceptGatewayTraceSink implements ConceptGatewayTraceSink {
    private final List<ConceptGatewayTraceRecord> records = new ArrayList<>();

    @Override
    public synchronized void append(ConceptGatewayTraceRecord record) {
        if (record != null) {
            records.add(record);
        }
    }

    @Override
    public synchronized List<ConceptGatewayTraceRecord> records() {
        return List.copyOf(records);
    }
}
